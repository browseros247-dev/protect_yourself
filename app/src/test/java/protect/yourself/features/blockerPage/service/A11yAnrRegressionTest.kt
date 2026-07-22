package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * A11Y-ANR-01 / SET-COUNTDOWN-01 / CRLOG-INIT-01 / LOG-SPAM-01 (v1.0.71)
 * static regression pins (same style as OverlayDependencyRemovedTest) — the
 * v1.0.69 field crashes must not creep back:
 *
 *  - 22 FATAL ANRs (5–12 s main-thread blocks): recursive tree walks did one
 *    binder IPC per node with only a node-count guard (300/500) assuming
 *    ~2 ms/IPC, plus TWO getRootInActiveWindow fetches per event.
 *  - "Invalid block-screen countdown stored (0)" persisted as a crash entry
 *    on EVERY read (CrashLoggingTree) — seeded sentinel "0".
 *  - CrashLogger breadcrumb load NPE'd on every cold start (declaration
 *    order) — all persisted breadcrumbs dropped.
 *  - DebugTree planted in release — full URLs / page text in logcat.
 */
class A11yAnrRegressionTest {

    private val moduleDir = File(".")
    private val mainDir = File(moduleDir, "src/main")

    private fun readSource(relative: String): String =
        File(mainDir, relative).readText()

    private val serviceSource: String by lazy {
        readSource("java/protect/yourself/features/blockerPage/service/MyAccessibilityService.kt")
    }

    private val manifest: String by lazy {
        File(mainDir, "AndroidManifest.xml").readText()
    }

    // ============================================================================
    // A11Y-ANR-01 — traversal ceilings
    // ============================================================================

    private fun constValue(name: String): Int {
        val m = Regex("$name = (\\d+)").find(serviceSource)
        assertWithMessage("constant $name must exist in MyAccessibilityService").that(m).isNotNull()
        return m!!.groupValues[1].toInt()
    }

    @Test
    fun `recursion depth cap is small - deep walks caused 30+ frame binder chains`() {
        assertThat(constValue("MAX_NODE_DEPTH")).isAtMost(12)
    }

    @Test
    fun `URL search node cap is bounded`() {
        assertThat(constValue("MAX_URL_SEARCH_NODES")).isAtMost(150)
    }

    @Test
    fun `text collection node cap is bounded`() {
        assertThat(constValue("MAX_TEXT_COLLECTION_NODES_CONST")).isAtMost(150)
    }

    @Test
    fun `hard wall-clock traversal budget exists (nodes alone cannot bound binder latency)`() {
        assertThat(serviceSource).contains("TRAVERSAL_TIME_BUDGET_MS")
        assertThat(constValue("TRAVERSAL_TIME_BUDGET_MS")).isAtMost(250)
    }

    @Test
    fun `traversals carry a TraversalBudget`() {
        val urlWalker = serviceSource.substringAfter("private fun findUrlInNode(", "")
        assertThat(urlWalker).isNotEmpty()
        assertThat(urlWalker.substringBefore(")")).contains("budget: TraversalBudget")
        val textWalker = serviceSource.substringAfter("private fun collectText(", "")
        assertThat(textWalker.substringBefore("budget: TraversalBudget")).doesNotContain("nodeCounter")
    }

    @Test
    fun `heavy content scan per package is throttled`() {
        assertThat(serviceSource).contains("HEAVY_SCAN_THROTTLE_MS")
        assertThat(serviceSource).contains("lastHeavyScanByPkg")
        val contentPath = serviceSource.substringAfter("private fun handleContentChange(", "")
            .substringBefore("private fun handleUrlDetected(")
        assertWithMessage("handleContentChange must throttle the heavy scan")
            .that(contentPath.contains("nowMs - lastScan < HEAVY_SCAN_THROTTLE_MS")).isTrue()
    }

    @Test
    fun `URL and text extractors share the caller's root - no per-event double root fetch`() {
        val urlFn = serviceSource.substringAfter("private fun extractUrlFromEvent(", "")
            .substringBefore("private fun findUrlInNode(")
        assertWithMessage("extractUrlFromEvent must not re-fetch the root")
            .that(urlFn.contains("rootInActiveWindow")).isFalse()
        val textFn = serviceSource.substringAfter("private fun extractTextFromEvent(", "")
            .substringBefore("private fun collectText(")
        assertWithMessage("extractTextFromEvent must not re-fetch the root")
            .that(textFn.contains("rootInActiveWindow")).isFalse()
    }

    @Test
    fun `hot per-event verbose logs are debug-gated (string not even built in release)`() {
        assertThat(serviceSource).containsMatch(
            "if \\(BuildConfig\\.DEBUG\\) \\{\\s*Timber\\.v\\(\"WindowStateChange"
        )
        assertThat(serviceSource).containsMatch(
            "if \\(BuildConfig\\.DEBUG\\) \\{\\s*Timber\\.v\\(\"PB-03: skipping duplicate"
        )
    }

    // ============================================================================
    // SET-COUNTDOWN-01 — no more WARN-per-read crash entries
    // ============================================================================

    @Test
    fun `database seeds a valid countdown (3) instead of the broken sentinel 0`() {
        val seed = readSource("java/protect/yourself/database/core/AppDatabaseCallback.kt")
        assertThat(seed).contains("""BLOCK_SCREEN_COUNT_DOWN_TIME_SET, "3"""")
        assertThat(seed).doesNotContain("""BLOCK_SCREEN_COUNT_DOWN_TIME_SET, "0"""")
    }

    @Test
    fun `countdown getter self-heals instead of warning on every read`() {
        val values = readSource("java/protect/yourself/database/switchStatus/SwitchStatusValues.kt")
        val getter = values.substringAfter("suspend fun getBlockScreenCountDownSeconds(): Int {", "")
            .substringBefore("/**\n")
        assertThat(getter).contains("SET-COUNTDOWN-01")
        assertThat(getter).contains("dao.upsert")
        // The healing persistence uses INFO, not WARN — WARN would be routed
        // into CrashLogger crash entries by CrashLoggingTree (the field bug).
        assertWithMessage("countdown getter must not use Timber.w (WARN reaches CrashLogger)")
            .that(getter.contains("Timber.w(")).isFalse()
        assertThat(getter.contains("Timber.i(")).isTrue()
    }

    // ============================================================================
    // CRLOG-INIT-01 — declaration order
    // ============================================================================

    @Test
    fun `CrashLogger breadcrumbBuffer is declared BEFORE init (monitor-enter NPE)`() {
        val src = readSource("java/protect/yourself/features/crashLog/CrashLogger.kt")
        val bufferIdx = src.indexOf("private val breadcrumbBuffer")
        val initIdx = src.indexOf("\n    init {")
        assertWithMessage("breadcrumbBuffer must be declared before init {}")
            .that(bufferIdx in 0 until initIdx).isTrue()
    }

    // ============================================================================
    // LOG-SPAM-01 — release logging hygiene
    // ============================================================================

    @Test
    fun `release builds plant ReleaseLogTree (INFO+) instead of DebugTree`() {
        val appSrc = readSource("java/protect/yourself/core/ProtectYourselfApp.kt")
        assertThat(appSrc).contains("ReleaseLogTree()")
        val timberInit = appSrc.substringAfter("private fun initTimberLog() {", "")
            .substringBefore("crashLogger?.let")
        assertThat(timberInit).contains("if (BuildConfig.DEBUG)")
    }

    // ============================================================================
    // MANIFEST — predictive-back opt-in (silences WindowOnBackDispatcher spam)
    // ============================================================================

    @Test
    fun `manifest enables OnBackInvokedCallback`() {
        assertThat(manifest).contains("""android:enableOnBackInvokedCallback="true"""")
    }
}
