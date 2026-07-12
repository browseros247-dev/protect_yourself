package protect.yourself.features.crashLog

import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * AnrWatchdog — detects Application Not Responding (ANR) events by polling
 * the main thread with a lightweight `Runnable` posted to the main `Handler`.
 *
 * # Why this exists
 *
 * ANRs do NOT throw and do NOT trigger `Thread.setDefaultUncaughtExceptionHandler`.
 * They are a system-level signal: when the main thread is blocked for ~5 seconds
 * (foreground) or ~200 seconds (background), Android shows the "App keeps
 * stopping" / "Wait / Close" dialog and writes a `traces.txt` file. Without
 * an explicit watchdog, ANRs are invisible to [CrashLogger] — the user
 * reports "the app froze" but there's no crash entry to diagnose.
 *
 * # How it works
 *
 * Every [intervalMs] (default 2.5 s — half the threshold), the watchdog
 * thread posts a tick `Runnable` to the main `Handler` and records the
 * expected tick time. The runnable, when it runs, sets `lastTickRanAt`
 * to the current elapsed-realtime. After waiting [thresholdMs] (default
 * 5 s), the watchdog checks: if `lastTickRanAt` is older than the threshold,
 * the main thread is blocked → log a FATAL ANR entry via [CrashLogger]
 * with the main-thread stack trace.
 *
 * The check runs on a background thread (the watchdog's own thread), so
 * the watchdog itself never blocks the main thread.
 *
 * # Tuning
 *
 * - [thresholdMs]: how long the main thread must be blocked before we log
 *   an ANR. Default 5000 ms (matches Android's foreground ANR threshold).
 *   Lower for more sensitive detection (more false positives); higher for
 *   fewer false positives (may miss short freezes).
 * - [intervalMs]: how often to post a tick. Default 2500 ms (half the
 *   threshold — Nyquist). Lower = faster detection but more CPU; higher =
 *   may miss a brief freeze that resolves before the next tick.
 *
 * # Limitations
 *
 * - This is a polling-based watchdog, not a true ANR detector. It detects
 *   "main thread blocked for N seconds" — which is the user-visible
 *   definition of an ANR but not exactly what ActivityManagerService uses.
 * - It cannot prevent the system's ANR dialog; it only logs the event so
 *   we have a crash entry for diagnosis.
 * - If the watchdog thread itself is killed (e.g. process death), no ANR
 *   will be logged. This is acceptable — process death is captured by the
 *   uncaught exception handler or next-launch detection.
 */
class AnrWatchdog(
    private val crashLogger: CrashLogger,
    private val thresholdMs: Long = DEFAULT_THRESHOLD_MS,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val watchdogThread = Thread({ runWatchdogLoop() }, "AnrWatchdog")
    private val running = AtomicBoolean(false)
    private val lastTickRanAt = AtomicLong(0L)
    private val anrReported = AtomicBoolean(false)

    /**
     * Start the watchdog. Safe to call multiple times — only the first call
     * starts the thread.
     */
    fun start() {
        if (running.compareAndSet(false, true)) {
            // Initialise lastTickRanAt so the first tick check doesn't
            // immediately fire a false positive.
            lastTickRanAt.set(SystemClock.elapsedRealtime())
            watchdogThread.isDaemon = true
            watchdogThread.start()
            Timber.i("ANR watchdog started (threshold=${thresholdMs}ms, interval=${intervalMs}ms)")
        }
    }

    /**
     * Stop the watchdog. The watchdog thread will exit on its next iteration.
     */
    fun stop() {
        running.set(false)
        watchdogThread.interrupt()
    }

    private fun runWatchdogLoop() {
        while (running.get()) {
            try {
                // Post a tick to the main thread
                val postedAt = SystemClock.elapsedRealtime()
                mainHandler.post {
                    lastTickRanAt.set(SystemClock.elapsedRealtime())
                    // Clear the ANR flag — main thread is responsive again
                    anrReported.set(false)
                }

                // Sleep for the interval (gives the main thread time to run the tick)
                Thread.sleep(intervalMs)

                // Check if the tick ran within the threshold
                val now = SystemClock.elapsedRealtime()
                val lastTick = lastTickRanAt.get()
                val timeSinceTick = now - lastTick

                if (timeSinceTick > thresholdMs && anrReported.compareAndSet(false, true)) {
                    // Main thread hasn't processed the tick within threshold.
                    // Before reporting an ANR, check if the main thread is
                    // actually BLOCKED or just IDLE (nativePollOnce).
                    //
                    // Crash log crash_20260711_124503_0010 (v1.0.46, vivo V2206)
                    // showed a false-positive ANR: the main thread stack trace
                    // was `nativePollOnce → Looper.loop → ActivityThread.main`
                    // — i.e. the main thread was IDLE (waiting for messages),
                    // not BLOCKED. This happens when the app is backgrounded
                    // and the system throttles the main Looper (common on
                    // Chinese OEMs: vivo, OPPO, Xiaomi, Huawei).
                    if (isMainThreadActuallyBlocked()) {
                        reportAnr(postedAt, timeSinceTick)
                    } else {
                        // Main thread is idle (nativePollOnce) — not a real ANR.
                        // Log at DEBUG level so we can see it in diagnostics but
                        // don't create a FATAL crash entry.
                        Timber.d("ANR watchdog: main thread idle (nativePollOnce) for ${timeSinceTick}ms — not a real ANR, skipping")
                    }
                }
            } catch (_: InterruptedException) {
                // stop() was called — exit the loop
                Thread.currentThread().interrupt()
                break
            } catch (t: Throwable) {
                // Don't let the watchdog itself die — log and continue
                Timber.e(t, "ANR watchdog iteration failed")
                try { Thread.sleep(intervalMs) } catch (_: InterruptedException) { break }
            }
        }
        Timber.i("ANR watchdog stopped")
    }

    /**
     * Check if the main thread is actually blocked (doing real work) vs
     * idle (sitting in nativePollOnce waiting for messages).
     *
     * When the main thread is idle, its top stack frame is:
     *   `android.os.MessageQueue.nativePollOnce(Native Method)`
     *
     * This is NOT an ANR — the main thread is perfectly healthy, just
     * waiting for something to do. This happens when:
     *   - The app is backgrounded
     *   - The system throttles the main Looper on some OEMs (vivo, OPPO, etc.)
     *   - There's simply no user interaction
     *
     * A real ANR has the main thread doing actual work:
     *   - `Database.execSQL` (DB I/O on main thread)
     *   - `File.readText` / `File.writeText` (disk I/O on main thread)
     *   - `Object.wait` / `synchronized` (lock contention)
     *   - `Thread.sleep` (explicit block)
     *   - Heavy computation
     */
    private fun isMainThreadActuallyBlocked(): Boolean {
        return try {
            val mainThread = Looper.getMainLooper().thread
            val stackTrace = mainThread.stackTrace
            if (stackTrace.isEmpty()) return true // Can't tell — assume blocked

            val topFrame = stackTrace[0]
            val className = topFrame.className
            val methodName = topFrame.methodName

            // If the top frame is nativePollOnce, the main thread is IDLE
            // (waiting for messages in the Looper), not BLOCKED.
            val isIdle = (className == "android.os.MessageQueue" && methodName == "nativePollOnce") ||
                (className == "android.os.MessageQueue" && methodName == "next") ||
                (className == "android.os.Looper" && methodName == "loopOnce") ||
                (className == "android.os.Looper" && methodName == "loop")

            !isIdle
        } catch (_: Throwable) {
            // Can't determine — assume blocked (safer to report than to miss)
            true
        }
    }

    /**
     * Report an ANR — capture the main-thread stack trace and log a FATAL
     * entry via CrashLogger. Only one ANR per "blocked episode" is logged
     * (anrReported flag prevents spamming if the main thread stays blocked
     * across multiple intervals).
     */
    private fun reportAnr(postedAt: Long, blockedForMs: Long) {
        try {
            val mainThread = Looper.getMainLooper().thread
            val stackTrace = mainThread.stackTrace.joinToString("\n") { "    at $it" }

            val anrThrowable = RuntimeException(
                "ANR detected — main thread blocked for ${blockedForMs}ms " +
                    "(threshold=${thresholdMs}ms)"
            )

            crashLogger.logThrowable(
                throwable = anrThrowable,
                severity = CrashSeverity.FATAL,
                tag = "ANRWatchdog",
                message = "Application Not Responding — main thread blocked for ${blockedForMs}ms",
                extraContext = mapOf(
                    "blockedForMs" to blockedForMs.toString(),
                    "thresholdMs" to thresholdMs.toString(),
                    "intervalMs" to intervalMs.toString(),
                    "postedAtElapsed" to postedAt.toString(),
                    "detectedAtElapsed" to SystemClock.elapsedRealtime().toString(),
                    "mainThreadName" to mainThread.name,
                    "mainThreadState" to mainThread.state.name,
                    "processId" to Process.myPid().toString(),
                    "mainThreadStackTrace" to stackTrace
                )
            )

            Timber.e("ANR detected — main thread blocked for ${blockedForMs}ms")
        } catch (t: Throwable) {
            // Never let the ANR reporter itself crash the watchdog
            Timber.e(t, "Failed to report ANR")
        }
    }

    companion object {
        const val DEFAULT_THRESHOLD_MS = 5000L  // 5 seconds (matches Android foreground ANR)
        const val DEFAULT_INTERVAL_MS = 2500L   // half the threshold (Nyquist)
    }
}
