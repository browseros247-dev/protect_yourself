package protect.yourself.features.crashLog

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * CRLOG-INIT-01 (v1.0.71): Kotlin initializes properties and `init` blocks in
 * declaration order. `breadcrumbBuffer` used to be declared ~760 lines BELOW
 * `init {}`, so `init → loadBreadcrumbsFromDisk() → synchronized(breadcrumbBuffer)`
 * hit a still-null buffer on every cold start with an existing
 * breadcrumbs.json:
 *
 *   NullPointerException: Null reference used for synchronization (monitor-enter)
 *   → caught by the load's catch → ALL persisted breadcrumbs silently dropped
 *   (field log: "Failed to load breadcrumbs from disk" at every process start;
 *   crash entries from later in that session had near-empty breadcrumbs).
 *
 * These tests instantiate the REAL CrashLogger against a Robolectric files
 * dir and pin: (a) init never throws with a pre-existing breadcrumbs.json,
 * (b) on-disk breadcrumbs are actually loaded, (c) appending still works and
 * keeps the loaded entries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashLoggerInitOrderTest {

    private lateinit var app: Application
    private lateinit var crashDir: File

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

    /** CrashLogger is a singleton — clear it between tests via reflection. */
    private fun resetSingleton() {
        // Kotlin emits companion-object vars as static fields on the outer class.
        val field = CrashLogger::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun recentBreadcrumbs(logger: CrashLogger): List<Breadcrumb> {
        val method = CrashLogger::class.java.getDeclaredMethod("getRecentBreadcrumbs")
        method.isAccessible = true
        return method.invoke(logger) as List<Breadcrumb>
    }

    private fun seedBreadcrumbsOnDisk(vararg messages: String) {
        val entries = messages.mapIndexed { i, msg ->
            """{"timestamp":${1000L + i},"timestampFormatted":"2026-07-22 20:00:0$i.000","category":"test","message":"$msg","data":{}}"""
        }
        File(crashDir, "breadcrumbs.json").writeText("[${entries.joinToString(",")}]", Charsets.UTF_8)
    }

    @Test
    fun `init with pre-existing breadcrumbs file - does not throw`() {
        seedBreadcrumbsOnDisk("bc-1", "bc-2")
        CrashLogger.init(app)
        assertThat(CrashLogger.get()).isNotNull()
    }

    @Test
    fun `on-disk breadcrumbs are loaded into the buffer`() {
        seedBreadcrumbsOnDisk("session-A-bc-1", "session-A-bc-2")
        CrashLogger.init(app)
        val logger = CrashLogger.get()!!
        val loaded = recentBreadcrumbs(logger)
        assertThat(loaded.map { it.message }).containsExactly("session-A-bc-1", "session-A-bc-2").inOrder()
    }

    @Test
    fun `appending keeps previously loaded breadcrumbs`() {
        seedBreadcrumbsOnDisk("old-breadcrumb")
        CrashLogger.init(app)
        val logger = CrashLogger.get()!!
        logger.logBreadcrumb("test", "new-breadcrumb")
        assertThat(recentBreadcrumbs(logger).map { it.message })
            .containsExactly("old-breadcrumb", "new-breadcrumb").inOrder()
    }

    @Test
    fun `missing breadcrumbs file - init clean, buffer starts empty`() {
        // no seedBreadcrumbsOnDisk call — file does not exist
        CrashLogger.init(app)
        val logger = CrashLogger.get()!!
        assertThat(recentBreadcrumbs(logger)).isEmpty()
        // and appending works from a cold start
        logger.logBreadcrumb("test", "first")
        assertThat(recentBreadcrumbs(logger).map { it.message }).containsExactly("first")
    }

    @Test
    fun `corrupt breadcrumbs file - init never throws, buffer usable`() {
        File(crashDir, "breadcrumbs.json").writeText("{ not json !!", Charsets.UTF_8)
        CrashLogger.init(app)
        val logger = CrashLogger.get()!!
        logger.logBreadcrumb("test", "after-corrupt")
        assertThat(recentBreadcrumbs(logger).map { it.message }).contains("after-corrupt")
    }
}
