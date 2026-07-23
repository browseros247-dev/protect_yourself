package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * PU-A11Y-PAGE-01 / PU-VPN-01 / A11Y-OVL-01 (v1.0.75 + v1.0.76) static pins.
 *
 * v1.0.76 mechanism upgrade (from the NopoX 1.0.53 reverse engineering):
 * while Prevent Uninstall is ON, our own accessibility-service detail page
 * and the system VPN settings page are COVERED by a
 * TYPE_ACCESSIBILITY_OVERLAY (2032) window drawn by the service —
 * the reference mechanism that is exempt from the obscuring-kill. The
 * v1.0.75 HOME eviction / activity-block paths survive only as fallbacks.
 */
class PuSettingsProtectionWiringTest {

    private val service: String by lazy {
        File(".", "src/main/java/protect/yourself/features/blockerPage/service/MyAccessibilityService.kt").readText()
    }
    private val screens: String by lazy {
        File(".", "src/main/java/protect/yourself/features/protectedApps/ProtectedSystemScreens.kt").readText()
    }
    private val overlay: String by lazy {
        File(".", "src/main/java/protect/yourself/features/blockerPage/utils/A11yBlockOverlay.kt").readText()
    }

    @Test
    fun `a11y page protection runs before the A11Y-KILL-01 early return and only when PU is on`() {
        val evictIdx = service.indexOf("evictFromOurA11yServicePage(")
        val killGuardIdx = service.indexOf("isAccessibilityManagementScreen(packageName, eventClassName)")
        assertThat(evictIdx).isGreaterThan(-1)
        assertThat(killGuardIdx).isGreaterThan(-1)
        assertThat(evictIdx).isLessThan(killGuardIdx)
        val callsite = service.substring(maxOf(0, evictIdx - 400), evictIdx)
        assertThat(callsite).contains("isPreventUninstallOn")
        assertThat(callsite).contains("com.android.systemui")
    }

    @Test
    fun `a11y page is covered by the overlay first - HOME eviction only as fallback`() {
        val methodStart = service.indexOf("private fun evictFromOurA11yServicePage")
        val methodEnd = service.indexOf("private fun isOurA11yServiceDetailPage")
        val body = service.substring(methodStart, methodEnd)
        // Overlay first, with the PU a11y-page message…
        assertThat(body).contains("A11yBlockOverlay.show(")
        assertThat(body).contains("pu_blocked_a11y_page_message")
        val overlayIdx = body.indexOf("A11yBlockOverlay.show(")
        val homeIdx = body.indexOf("performGlobalAction(GLOBAL_ACTION_HOME)")
        // …and the HOME press exists ONLY behind it (fallback path, after the overlay attempt).
        assertThat(homeIdx).isGreaterThan(overlayIdx)
        assertThat(body).contains("overlay failed — HOME eviction fallback")
        // The fallback is suppressed inside the service-connect cool-down.
        assertThat(body).contains("serviceConnectCoolDownUntilMs")
        assertThat(body).contains("maybeTriggerSelfHealOnA11yScreen")  // keep the service armed
        assertThat(body).contains("A11Y_PAGE_PROBE_THROTTLE_MS")
        assertThat(body).contains("A11Y_PAGE_KICK_THROTTLE_MS")
    }

    @Test
    fun `eviction toast remains only in the fallback and stays delayed and throttled`() {
        assertThat(service).contains("showPuEvictionToast()")
        assertThat(service).contains("PU_KICK_TOAST_DELAY_MS")
        assertThat(service).contains("PU_KICK_TOAST_THROTTLE_MS")
        assertThat(service).contains("pu_blocked_a11y_page_toast")
        assertThat(service).contains("Looper.getMainLooper()")
    }

    @Test
    fun `connect cool-down exists and is armed on onServiceConnected`() {
        assertThat(service).contains("SERVICE_CONNECT_COOLDOWN_MS = 5_000L")
        assertThat(service).contains("private var serviceConnectCoolDownUntilMs")
        val connectIdx = service.indexOf("override fun onServiceConnected()")
        assertThat(connectIdx).isGreaterThan(-1)
        val region = service.substring(connectIdx, connectIdx + 900)
        assertThat(region).contains("serviceConnectCoolDownUntilMs =")
        assertThat(region).contains("SERVICE_CONNECT_COOLDOWN_MS")
    }

    @Test
    fun `vpn settings screen uses the overlay first with the PU activity block as fallback`() {
        val vpnIdx = service.indexOf("isVpnSettingsScreen(packageName, className)")
        assertThat(vpnIdx).isGreaterThan(-1)
        val around = service.substring(maxOf(0, vpnIdx - 1200), vpnIdx + 700)
        assertThat(around).contains("isPreventUninstallOn")
        assertThat(around).contains("A11yBlockOverlay.show(")
        assertThat(around).contains("pu_blocked_vpn_settings_message")
        // Activity fallback retained behind the overlay attempt.
        assertThat(around).contains("launchBlockActivity(packageName, \"pu_blocked_vpn_settings_message\"")
        // The choke-point guard remains the safety net for the ACTIVITY path over a11y screens.
        assertThat(service).contains("Block suppressed over a11y-management screen")
    }

    @Test
    fun `overlay teardown hygiene - hidden on unbind`() {
        val unbindIdx = service.indexOf("override fun onUnbind")
        assertThat(unbindIdx).isGreaterThan(-1)
        val region = service.substring(unbindIdx, unbindIdx + 700)
        assertThat(region).contains("A11yBlockOverlay.hide()")
    }

    @Test
    fun `pure helpers exist with the pinned semantics`() {
        assertThat(screens).contains("fun isVpnSettingsScreen(packageName: String, className: String)")
        assertThat(screens).contains("fun detailOnlyFingerprint(")
        assertThat(screens).contains("VPN_SETTINGS_CLASS_MARKER")
        assertThat(screens).contains("Network overview page")
    }

    @Test
    fun `overlay is the 2032 full-screen touchable sticky surface of the reference`() {
        assertThat(overlay).contains("TYPE_ACCESSIBILITY_OVERLAY")
        assertThat(overlay).contains("OVERLAY_FLAGS")
        assertThat(overlay).contains("FLAG_LAYOUT_IN_SCREEN or")
        assertThat(overlay).contains("FLAG_NOT_TOUCH_MODAL or")
        assertThat(overlay).contains("FLAG_NOT_FOCUSABLE")
        assertThat(overlay).contains("MATCH_PARENT")
        assertThat(overlay).contains("TRANSLUCENT")
        // Sticky singleton — re-show while visible only swaps the message.
        assertThat(overlay).contains("if (isShowing && overlayView != null)")
        // Close parity with PornBlockActivity (gate + countdown + HOME landing).
        assertThat(overlay).contains("CloseGatePolicy(")
        assertThat(overlay).contains("getBlockScreenCountDownSeconds()")
        assertThat(overlay).contains("Intent.CATEGORY_HOME")
        // No app-level window is ever created here besides the overlay.
        assertThat(overlay).doesNotContain("PornBlockActivity::class")
        assertThat(overlay).doesNotContain("startActivityForResult")
    }

    @Test
    fun `strings for all PU surfaces exist in resources`() {
        val strings = File(".", "src/main/res/values/strings.xml").readText()
        assertThat(strings).contains("name=\"pu_blocked_vpn_settings_message\"")
        assertThat(strings).contains("name=\"pu_blocked_a11y_page_message\"")
        assertThat(strings).contains("name=\"pu_blocked_a11y_page_toast\"")
        assertThat(strings).contains("name=\"block_page_default_pu_message\"")
    }
}
