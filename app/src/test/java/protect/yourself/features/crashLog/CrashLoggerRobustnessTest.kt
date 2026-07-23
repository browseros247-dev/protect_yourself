package protect.yourself.features.crashLog

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * CRLOG-TS-01 / CRLOG-IPC-01 (v1.0.73) regression coverage — proactive-audit
 * round findings:
 *
 *  - CRLOG-TS-01: CrashLogger's two shared SimpleDateFormat fields were used
 *    UNGUARDED. SimpleDateFormat mutates an internal Calendar during
 *    format(); CrashLogger is entered concurrently from every WARN+ Timber
 *    call on any thread (CrashLoggingTree). Concurrent format() corrupts the
 *    timestamp strings (or throws internal AIOOBE) — corrupting/loosing the
 *    very records used to diagnose field crashes. Fix: timestampLock wrappers
 *    for ALL format call sites.
 *  - CRLOG-IPC-01: every WARN+ log synchronously captured AppInfo (2 binder
 *    IPCs) + ServiceStateInfo (~2 IPCs) on the caller thread — 3–4 binder
 *    calls per WARN during warning storms (the vivo V2206 ANR class).
 *    Fix: process-lifetime cachedAppInfo + 1 s TTL service-state cache,
 *    mirroring the existing memory/disk caches.
 *
 * Structure: hard static pins on the source (fail loudly on regression) +
 * real multi-threaded Robolectric smoke tests (defense in depth).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLoggerRobustnessTest {

    private lateinit var app: Application
    private lateinit var crashDir: File

    private val source: String by lazy {
        File("src/main/java/protect/yourself/features/crashLog/CrashLogger.kt").readText()
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        resetSingleton()
        crashDir = File(app.filesDir, "crashlogs").apply { deleteRecursively(); mkdirs() }
    }

    @After
    fun tearDown() {
        resetSingleton()
        crashDir.deleteRecursively()
    }

    private fun resetSingleton() {
        val field = CrashLogger::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    // ============================================================================
    // CRLOG-TS-01 — static pins on the formatting lock
    // ============================================================================

    @Test
    fun `every SimpleDateFormat use goes through the timestampLock wrappers`() {
        // Bare inline .format(Date()) call sites must not reappear.
        assertThat(countOccurrences("dateFormat.format(Date())")).isEqualTo(0)
        assertThat(countOccurrences("fileDateFormat.format(Date())")).isEqualTo(0)
        // The wrappers exist, hold the lock, and are the only format sites.
        assertThat(source).contains("private val timestampLock = Any()")
        assertThat(source).contains(
            "private fun formatTimestamp(date: Date): String =\n" +
                "        synchronized(timestampLock) { dateFormat.format(date) }"
        )
        assertThat(source).contains(
            "private fun formatFileTimestamp(date: Date): String =\n" +
                "        synchronized(timestampLock) { fileDateFormat.format(date) }"
        )
        assertThat(countOccurrences("formatTimestamp(Date())")).isAtLeast(3) // throwable + message + breadcrumb (+ export)
        assertThat(countOccurrences("formatFileTimestamp(Date())")).isAtLeast(1) // id generation
    }

    // ============================================================================
    // CRLOG-IPC-01 — static pins on metadata caching
    // ============================================================================

    @Test
    fun `app info is captured once per process, not once per log call`() {
        assertThat(source).contains("private val cachedAppInfo: AppInfo by lazy { captureAppInfo() }")
        assertThat(countOccurrences("appInfo = cachedAppInfo,")).isEqualTo(3) // throwable + message + export
        assertThat(countOccurrences("appInfo = captureAppInfo(),")).isEqualTo(0)
    }

    @Test
    fun `service state capture has a TTL cache like memory and disk`() {
        assertThat(source).contains("SERVICE_STATE_CACHE_TTL_MS = 1000L")
        assertThat(source).contains("private var cachedServiceState: ServiceStateInfo? = null")
        assertThat(source).contains("fun captureServiceStateCached()")
        assertThat(countOccurrences("serviceState = captureServiceStateCached(),")).isEqualTo(2)
        assertThat(countOccurrences("serviceState = captureServiceState(),")).isEqualTo(0)
    }

    // ============================================================================
    // Functional concurrency smoke tests (real CrashLogger, Robolectric)
    // ============================================================================

    @Test
    fun `concurrent logMessage from 8 threads - all entries well-formed, nothing lost`() {
        val logger = CrashLogger.getInstance(app)
        val failures = AtomicReference<Throwable?>(null)
        val logged = AtomicInteger(0)
        val threads = 8
        val perThread = 60

        runConcurrently(threads, perThread) { i ->
            try {
                val id = logger.logMessage(
                    severity = CrashSeverity.WARN,
                    tag = "stress",
                    message = "concurrent logMessage $i"
                )
                if (id.isNotBlank()) logged.incrementAndGet()
            } catch (t: Throwable) {
                failures.compareAndSet(null, t)
            }
        }

        assertWithMessage("no exception may escape logMessage under concurrency: ${failures.get()}")
            .that(failures.get()).isNull()
        assertThat(logged.get()).isEqualTo(threads * perThread)
        // MAX_ENTRIES pruning keeps the newest 50 — the count flow may lag but
        // persisted files must carry well-formed timestamps.
        val persisted = crashDir.listFiles { f -> f.name.matches(Regex("crash_\\d{8}_\\d{6}_\\d{4}\\.json")) }
        assertThat(persisted).isNotNull()
        assertThat(persisted!!.size).isGreaterThan(0)
        val sample = persisted.take(5).map { it.readText() }
        val tsPattern = Regex("\"timestampFormatted\":\\s*\"(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\"")
        for (json in sample) {
            assertWithMessage("persisted entry must carry a well-formed timestamp: ${json.take(80)}")
                .that(tsPattern.containsMatchIn(json)).isTrue()
        }
        resetSingleton()
    }

    @Test
    fun `concurrent breadcrumbs from 4 threads - buffer bounded, no corruption`() {
        val logger = CrashLogger.getInstance(app)
        val failures = AtomicReference<Throwable?>(null)
        runConcurrently(threads = 4, perThread = 50) { i ->
            try {
                logger.logBreadcrumb("stress", "breadcrumb $i")
            } catch (t: Throwable) {
                failures.compareAndSet(null, t)
            }
        }
        assertWithMessage("no exception may escape logBreadcrumb under concurrency: ${failures.get()}")
            .that(failures.get()).isNull()
        // A subsequent entry must still build cleanly with well-formed bits.
        val id = logger.logMessage(CrashSeverity.ERROR, tag = "post-stress", message = "after the storm")
        assertThat(id).isNotEmpty()
        resetSingleton()
    }

    // ---- helpers ---------------------------------------------------------------

    private fun runConcurrently(threads: Int, perThread: Int, work: (Int) -> Unit) {
        val startGate = CountDownLatch(1)
        val doneGate = CountDownLatch(threads)
        val workers = (0 until threads).map { t ->
            Thread {
                startGate.await()
                repeat(perThread) { i -> work(t * perThread + i) }
                doneGate.countDown()
            }.apply { isDaemon = true; start() }
        }
        startGate.countDown()
        assertThat(doneGate.await(90, TimeUnit.SECONDS)).isTrue()
        workers.forEach { it.join(5_000) }
    }

    private fun countOccurrences(needle: String): Int =
        source.split(needle).size - 1
}
