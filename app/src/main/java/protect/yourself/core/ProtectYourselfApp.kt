package protect.yourself.core

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import kotlinx.coroutines.launch
import protect.yourself.BuildConfig
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.crashLog.AnrWatchdog
import protect.yourself.features.crashLog.CrashLogger
import protect.yourself.features.crashLog.CrashLoggingTree
import protect.yourself.features.crashLog.CrashSeverity
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Application class for Protect Yourself.
 *
 * Extends Application directly (NO signature killer, NO Firebase).
 *
 * CRASH PREVENTION + LOGGING STRATEGY:
 * 1. All auto-initializers disabled in AndroidManifest.xml (FirebaseInitProvider,
 *    WorkManagerInitializer, ProcessLifecycleInitializer, EmojiCompat, ProfileInstaller)
 * 2. Everything initialized manually here in onCreate() with try/catch
 * 3. CrashLogger initialised FIRST — captures structured crash entries with full
 *    diagnostic context (device info, app info, memory, disk, logcat tail,
 *    breadcrumbs, stack trace, cause chain, service state). Persisted to
 *    filesDir/crashlogs/. Breadcrumbs are disk-backed so they survive hard
 *    crashes. Atomic writes (temp + rename) prevent corruption on process kill.
 * 4. Global uncaught exception handler routes to CrashLogger (severity FATAL)
 *    before delegating to the previous handler. Recursive-crash protection
 *    via AtomicBoolean flag prevents infinite recursion if the crash handler
 *    itself throws.
 * 5. ANR watchdog posts a tick Runnable to the main Handler every 2.5 s; if
 *    the tick doesn't run within 5 s, logs a FATAL ANR entry with the
 *    main-thread stack trace.
 * 6. CrashLoggingTree planted alongside Timber.DebugTree so all Timber.e()/w()
 *    calls are captured in the structured crash log too.
 * 7. On next launch after a FATAL crash, a high-priority notification is
 *    posted so the user knows a crash happened and can view the details.
 *
 * Firebase was removed entirely because FirebaseInitProvider auto-initializes
 * Firebase BEFORE Application.onCreate() and crashes if the project config is
 * invalid. The app's core features don't need Firebase — CrashLogger provides
 * comprehensive offline crash analysis instead.
 */
class ProtectYourselfApp : Application(), DefaultLifecycleObserver, Configuration.Provider {

    lateinit var appContainer: AppContainer
        private set

    override fun onCreate() {
        super<Application>.onCreate()

        // 0. Initialise CrashLogger FIRST — so any crash during subsequent init
        //    steps is captured with full diagnostic context.
        //    Must happen before installCrashHandler() and before any Timber calls.
        //    Wrapped in safeInit so a CrashLogger init failure (e.g. filesDir
        //    inaccessible) doesn't crash the whole app — the rest of the app
        //    already null-checks `crashLogger?`.
        safeInit("CrashLogger") { initCrashLogger() }

        // 1. Install global uncaught exception handler (routes to CrashLogger).
        //    Has recursive-crash protection via AtomicBoolean flag.
        installCrashHandler()

        // 2. Init Timber logging — plants DebugTree + CrashLoggingTree so all
        //    Timber.e()/w() calls are captured in the structured crash log.
        safeInit("TimberLog") { initTimberLog() }

        // Drop a startup breadcrumb so future crashes show app startup time
        crashLogger?.logBreadcrumb("AppLifecycle", "Application.onCreate started")

        // 3. Init Room database (non-blocking — just creates the instance)
        safeInit("RoomDB") { AppDatabase.getInstance(this) }

        // 4. Lifecycle observer (uses DefaultLifecycleObserver, not the
        //    deprecated @OnLifecycleEvent annotation)
        safeInit("LifecycleObserver") {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        }

        // 5. App container (manual DI) — initialize directly (not in safeInit)
        // so lateinit var is always set
        appContainer = AppContainer(this)

        // 6. Initialize PackageManagerProvider for app picker
        safeInit("PackageManagerProvider") {
            protect.yourself.commons.utils.PackageManagerProvider.init(this)
        }

        // 7. Accessibility self-heal + guard
        //    PackageManagerProvider MUST be initialised before this step
        //    because AccessibilityPersistUtils.ownComponentFlat calls
        //    PackageManagerProvider.getPackageName() lazily.
        //
        // BUGFIX (v1.0.49): moved selfHealSafe to a BACKGROUND coroutine.
        // selfHealSafe performs blocking IPC calls to Settings.Secure and
        // PackageManager.getInstalledPackages — each can block 100-500ms on
        // some OEMs (vivo, OPPO, Xiaomi). On a vivo V2206, these combined
        // contributed to ANR symptoms. The AccessibilityGuard.startWatching
        // call is lightweight (registers a ContentObserver) so stays on main.
        safeInit("AccessibilityGuard") {
            try {
                protect.yourself.commons.utils.PackageManagerProvider.init(this)
            } catch (_: Throwable) {}
            protect.yourself.core.appCoroutineScope(
                scopeName = "ProtectYourselfApp-selfHealSafe",
                dispatcher = kotlinx.coroutines.Dispatchers.IO
            ).launch {
                try {
                    protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(this@ProtectYourselfApp)
                    Timber.d("selfHealSafe completed in onCreate (background)")
                } catch (t: Throwable) {
                    Timber.w(t, "selfHealSafe in onCreate failed (background)")
                }
            }
            protect.yourself.features.protectedApps.AccessibilityGuard.getInstance()
                .startWatching(this)
        }

        // 8. Schedule periodic workers (non-critical — app works without them)
        safeInit("WorkManager") {
            protect.yourself.commons.utils.workManager.WorkerUtils.getInstance()
                .initAppDataCheckWorker(this)
            // Phase 2: Schedule restrictions check worker (15-min periodic safety net)
            protect.yourself.commons.utils.workManager.ScheduleCheckWorker.enqueue(this)
        }

        // 8b. Initialize theme preferences (Light/Dark/System)
        safeInit("ThemePreferences") {
            protect.yourself.theme.ThemePreferences.init(this)
        }

        // 9. Create notification channels
        safeInit("NotificationChannels") {
            protect.yourself.commons.utils.notificationUtils.NotificationHelper
                .createAllChannels(this)
            // Crash-detected notification channel (separate so the user can
            // customise its importance independently of other notifications)
            createCrashNotificationChannel()
        }

        // 10. Start ANR watchdog — detects Application Not Responding events
        //     that don't throw and don't trigger the uncaught exception handler.
        safeInit("AnrWatchdog") {
            crashLogger?.let { logger ->
                AnrWatchdog(logger).start()
            }
        }

        // 11. Check for crashes from the previous session — if any FATAL
        //     entries exist with timestamp > lastLaunchTime, post a
        //     "crash detected" notification so the user knows.
        safeInit("CrashNotification") { notifyIfCrashedSinceLastLaunch() }

        // Record this launch time for next-launch comparison
        updateLastLaunchTime()

        crashLogger?.logBreadcrumb("AppLifecycle", "Application.onCreate completed")
        Timber.i("Protect Yourself v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) initialized")
        Log.i(TAG, "App initialized: $packageName")
    }

    override fun onStop(owner: LifecycleOwner) {
        Timber.d("App backgrounded")
        crashLogger?.logBreadcrumb("AppLifecycle", "App backgrounded")
    }

    override fun onStart(owner: LifecycleOwner) {
        crashLogger?.logBreadcrumb("AppLifecycle", "App foregrounded")
    }

    /**
     * WorkManager Configuration.Provider — enables on-demand initialization.
     * WorkManager is initialized when first accessed, NOT during app startup.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(if (BuildConfig.DEBUG) Log.DEBUG else Log.INFO)
            .build()

    /**
     * Wrapper that catches any exception from an init step and logs it
     * to CrashLogger + logcat without crashing the app.
     */
    private fun safeInit(stepName: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            Timber.e(t, "Init step '$stepName' failed (non-fatal)")
            Log.e(TAG, "Init step '$stepName' failed", t)
            crashLogger?.logThrowable(
                throwable = t,
                severity = CrashSeverity.ERROR,
                tag = "AppInit",
                message = "Init step '$stepName' failed (non-fatal)",
                extraContext = mapOf("initStep" to stepName)
            )
        }
    }

    private fun initCrashLogger() {
        CrashLogger.init(this)
        crashLogger = CrashLogger.get()
        // Reset the once-per-session log dedup tracker — each new app session
        // starts fresh so warnings can be logged again.
        protect.yourself.commons.utils.OncePerSessionLogger.resetAll()
    }

    private fun initTimberLog() {
        // Plant DebugTree for logcat + CrashLoggingTree for structured crash logs
        Timber.plant(Timber.DebugTree())
        crashLogger?.let { Timber.plant(CrashLoggingTree(it)) }
    }

    /**
     * Install a global uncaught exception handler that routes crashes to
     * CrashLogger with severity FATAL (full diagnostic context captured)
     * before delegating to the previous handler (so the OS still handles
     * the crash normally — shows "App keeps stopping" dialog etc.).
     *
     * # Recursive crash protection
     *
     * If `crashLogger?.logThrowable` itself throws (e.g. OOM during Gson
     * serialisation), the inner catch swallows it. We also set an
     * `inCrashHandler` flag on the CrashLogger so re-entrant calls to
     * `logThrowable` are suppressed — preventing infinite recursion if the
     * crash handler itself crashes.
     *
     * If delegating to the previous handler throws (degraded runtime), we
     * fall back to `Process.killProcess` + `exitProcess` as a last resort
     * so the process definitely dies (otherwise we'd be in an undefined
     * state with a live but broken process).
     */
    private fun installCrashHandler() {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                // Set re-entrancy flag — if logThrowable itself throws and
                // triggers another uncaught exception, the inner call will
                // bail out instead of recursing infinitely.
                crashLogger?.setInCrashHandler(true)
                crashLogger?.logThrowable(
                    throwable = throwable,
                    severity = CrashSeverity.FATAL,
                    tag = "UncaughtExceptionHandler",
                    message = "Uncaught exception on thread ${thread.name}",
                    extraContext = mapOf(
                        "threadName" to thread.name,
                        "threadId" to thread.id.toString(),
                        "isDaemon" to thread.isDaemon.toString(),
                        "isMainThread" to (thread === android.os.Looper.getMainLooper().thread).toString()
                    )
                )
                android.util.Log.e(TAG, "FATAL CRASH on ${thread.name}: ${throwable.message}", throwable)
            } catch (_: Throwable) {
                // Can't even log the crash — give up gracefully but continue
                // to delegate so the OS still handles the crash.
                android.util.Log.e(TAG, "CrashLogger failed during FATAL crash logging", throwable)
            } finally {
                crashLogger?.setInCrashHandler(false)
            }
            // Delegate to the previous handler so the OS still gets the crash.
            // Wrapped in try/catch because the previous handler may itself
            // throw in a degraded runtime state.
            try {
                previousHandler?.uncaughtException(thread, throwable)
            } catch (_: Throwable) {
                // Last resort — kill the process so we don't end up in an
                // undefined state with a live but broken process.
                android.util.Log.e(TAG, "Previous uncaught handler threw — killing process")
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    /**
     * Create the notification channel for crash-detected notifications.
     * Separate from the app's other notification channels so the user can
     * customise its importance independently.
     */
    private fun createCrashNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CRASH_NOTIFICATION_CHANNEL_ID,
                "Crash alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies you when the app crashed in the background"
                enableVibration(true)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    /**
     * Check for FATAL crash entries with timestamp > lastLaunchTime. If any
     * exist, post a high-priority notification so the user knows the app
     * crashed. Without this, the user has no idea a crash happened — they
     * have to manually navigate to Profile → Crash Logs to discover it.
     */
    private fun notifyIfCrashedSinceLastLaunch() {
        val logger = crashLogger ?: return
        val lastLaunch = getLastLaunchTime()
        val recentFatal = try {
            logger.readEntries(limit = 20).firstOrNull {
                it.severity == CrashSeverity.FATAL && it.timestamp > lastLaunch
            }
        } catch (_: Throwable) { null }
        if (recentFatal != null) {
            postCrashDetectedNotification(recentFatal)
        }
    }

    private fun postCrashDetectedNotification(entry: protect.yourself.features.crashLog.CrashLogEntry) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

            // Build a PendingIntent to open the Crash Logs page
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            val pendingIntent = intent?.let {
                android.app.PendingIntent.getActivity(
                    this,
                    0,
                    it,
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
            }

            val notification = NotificationCompat.Builder(this, CRASH_NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Protect Yourself crashed")
                .setContentText(
                    "The app crashed unexpectedly last time. Tap to view details."
                )
                .setStyle(NotificationCompat.BigTextStyle()
                    .bigText(
                        "The app crashed unexpectedly last time (${entry.timestampFormatted}). " +
                            "Tap to view the crash log and share it with the developer if needed."
                    ))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build()

            nm.notify(CRASH_NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to post crash-detected notification")
        }
    }

    private fun getLastLaunchTime(): Long {
        val prefs = getSharedPreferences("crash_logger_prefs", Context.MODE_PRIVATE)
        return prefs.getLong("lastLaunchTime", 0L)
    }

    private fun updateLastLaunchTime() {
        val prefs = getSharedPreferences("crash_logger_prefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("lastLaunchTime", System.currentTimeMillis()).apply()
    }

    companion object {
        private const val TAG = "ProtectYourselfApp"
        private const val CRASH_NOTIFICATION_CHANNEL_ID = "crash_alerts"
        private const val CRASH_NOTIFICATION_ID = 9001

        @Volatile
        private var crashLogger: CrashLogger? = null

        /**
         * Get the CrashLogger singleton (initialized in onCreate).
         * Returns null if not yet initialised.
         */
        fun getCrashLogger(): CrashLogger? = crashLogger
    }
}
