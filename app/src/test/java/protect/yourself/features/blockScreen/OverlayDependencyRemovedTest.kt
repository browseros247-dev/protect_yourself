package protect.yourself.features.blockScreen

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * ACTIVITY-BLOCK-01 (v1.0.70) regression pins for the overlay dependency
 * removal — "the block screen must not depend on Display over other apps".
 *
 * Static guards (same style as ProguardRulesRegressionTest): they read the
 * real sources/manifest (unit-test working dir = app module) so the
 * dependency can never silently creep back:
 *
 *  - No `SYSTEM_ALERT_WINDOW` in the manifest, no `TYPE_APPLICATION_OVERLAY`
 *    usage, no `Settings.canDrawOverlays` gate in the block path.
 *  - No `BlockOverlayManager` file, no overlay references in the service,
 *    no overlay-permission prompts (notification / settings row).
 *  - `PornBlockActivity` is declared with the transparent block theme and
 *    that theme is actually translucent in BOTH day and night resources.
 */
class OverlayDependencyRemovedTest {

    private val moduleDir = File(".")
    private val mainDir = File(moduleDir, "src/main")

    private val manifest: String by lazy {
        File(mainDir, "AndroidManifest.xml").readText()
    }

    private fun readSource(relative: String): String =
        File(mainDir, relative).readText()

    private val serviceSource: String by lazy {
        readSource("java/protect/yourself/features/blockerPage/service/MyAccessibilityService.kt")
    }

    // ============================================================================
    // Manifest
    // ============================================================================

    @Test
    fun `manifest no longer declares SYSTEM_ALERT_WINDOW`() {
        assertThat(manifest).doesNotContain("android.permission.SYSTEM_ALERT_WINDOW")
    }

    @Test
    fun `PornBlockActivity uses the transparent block theme`() {
        val activityBlock =
            manifest.substringAfter("protect.yourself.features.blockerPage.ui.PornBlockActivity")
                .substringBefore("/>")
        assertThat(activityBlock).contains("android:theme=\"@style/Theme.TransparentBlock\"")
        // singleTop + excludeFromRecents semantics preserved
        assertThat(activityBlock).contains("android:launchMode=\"singleTop\"")
        assertThat(activityBlock).contains("android:excludeFromRecents=\"true\"")
    }

    @Test
    fun `transparent block theme is translucent in day and night resources`() {
        listOf("res/values/themes.xml", "res/values-night/themes.xml").forEach { res ->
            val text = readSource(res)
            assertWithMessage("$res defines Theme.TransparentBlock")
                .that(text.contains("Theme.TransparentBlock")).isTrue()
            val style = text.substringAfter("name=\"Theme.TransparentBlock\"").substringBefore("</style>")
            assertThat(style).contains("android:windowIsTranslucent\">true")
            assertThat(style).contains("android:windowBackground\">@android:color/transparent")
            assertThat(style).contains("android:windowDisablePreview\">true")
        }
    }

    // ============================================================================
    // Sources — no WindowManager overlay path anywhere
    // ============================================================================

    @Test
    fun `no TYPE_APPLICATION_OVERLAY usage remains in production sources`() {
        val hits = File(mainDir, "java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                file.readLines().any { line ->
                    line.contains("TYPE_APPLICATION_OVERLAY") &&
                        !line.trimStart().startsWith("*") &&
                        !line.trimStart().startsWith("//")
                }
            }
            .map { it.name }
            .toList()
        assertWithMessage("sources still using TYPE_APPLICATION_OVERLAY: $hits")
            .that(hits).isEmpty()
    }

    @Test
    fun `BlockOverlayManager file is gone`() {
        assertThat(
            File(mainDir, "java/protect/yourself/features/blockerPage/service/BlockOverlayManager.kt").exists()
        ).isFalse()
    }

    @Test
    fun `service has no overlay references and goes straight to the activity block screen`() {
        assertThat(serviceSource).doesNotContain("BlockOverlayManager(")
        assertThat(serviceSource).doesNotContain("showBlockOverlay")
        assertThat(serviceSource).doesNotContain("hideBlockOverlay")
        assertThat(serviceSource).doesNotContain("Settings.canDrawOverlays")
        assertThat(serviceSource).doesNotContain("showOverlayPermissionNotification")
        // Try/verify/launch plumbing present instead
        assertThat(serviceSource).contains("tryLaunchBlockScreen")
        assertThat(serviceSource).contains("PornBlockActivity.isShowing")
    }

    @Test
    fun `no overlay-permission prompts remain in settings UI or notifications`() {
        val viewModel = readSource("java/protect/yourself/features/blockerPage/BlockerPageViewModel.kt")
        val home = readSource("java/protect/yourself/features/blockerPage/components/BlockerPageHome.kt")
        val notif = readSource("java/protect/yourself/commons/utils/notificationUtils/NotificationHelper.kt")

        assertThat(viewModel).doesNotContain("DISPLAY_POPUP_WINDOW_PERMISSION")
        assertThat(viewModel).doesNotContain("OpenOverlaySettings")
        assertThat(home).doesNotContain("OpenOverlaySettings")
        assertThat(home).doesNotContain("ACTION_MANAGE_OVERLAY_PERMISSION")
        assertThat(notif).doesNotContain("showOverlayPermissionNotification")
    }

    // ============================================================================
    // A11Y-KILL-01 — the block engine can never cover its own death screen
    // ============================================================================

    @Test
    fun `service guards block decisions with ProtectedSystemScreens`() {
        assertThat(serviceSource).contains("isAccessibilityManagementScreen")
        assertThat(serviceSource).contains("maybeTriggerSelfHealOnA11yScreen")
        // The lethal "block the a11y settings page for our service" rule
        // (v1.0.68-era Check 4) must never come back.
        assertThat(serviceSource).doesNotContain("blocking accessibility settings page for our service")
    }

    @Test
    fun `close gate is synchronous and never unarms (CLOSE-BTN-01)`() {
        val activity = readSource("java/protect/yourself/features/blockerPage/ui/PornBlockActivity.kt")
        assertThat(activity).contains("CloseGatePolicy")
        // The old asynchronous arm-after-onFinish-with-second-DB-read pattern
        // is what left the button dead — it must not come back.
        val onFinishOfCountdown = activity.substringAfter("override fun onFinish()", "")
        assertThat(onFinishOfCountdown.substringBefore("}.start()")).doesNotContain("setOnClickListener")
        assertThat(File(mainDir, "java/protect/yourself/features/blockerPage/ui/CloseGatePolicy.kt").exists()).isTrue()
    }
}
