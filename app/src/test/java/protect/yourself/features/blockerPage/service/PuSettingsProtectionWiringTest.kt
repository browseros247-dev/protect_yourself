package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * PU-A11Y-PAGE-01 / PU-VPN-01 (v1.0.75) static pins — while Prevent
 * Uninstall is ON the service must (a) evict the user from OUR OWN
 * accessibility-service detail page WITHOUT ever drawing over it
 * (A11Y-KILL-01 constraint) and (b) block the system VPN settings screen
 * with the standard PU block flow.
 */
class PuSettingsProtectionWiringTest {

    private val service: String by lazy {
        File(".", "src/main/java/protect/yourself/features/blockerPage/service/MyAccessibilityService.kt").readText()
    }
    private val screens: String by lazy {
        File(".", "src/main/java/protect/yourself/features/protectedApps/ProtectedSystemScreens.kt").readText()
    }

    @Test
    fun `a11y page eviction runs before the A11Y-KILL-01 early return and only when PU is on`() {
        val evictIdx = service.indexOf("evictFromOurA11yServicePage(")
        val killGuardIdx = service.indexOf("isAccessibilityManagementScreen(packageName, eventClassName)")
        assertThat(evictIdx).isGreaterThan(-1)
        assertThat(killGuardIdx).isGreaterThan(-1)
        assertThat(evictIdx).isLessThan(killGuardIdx)
        // Gated on the Prevent-Uninstall switch and other apps only.
        val callsite = service.substring(maxOf(0, evictIdx - 400), evictIdx)
        assertThat(callsite).contains("isPreventUninstallOn")
        assertThat(callsite).contains("com.android.systemui")
    }

    @Test
    fun `a11y page eviction never draws over the page - HOME global action only`() {
        val methodStart = service.indexOf("private fun evictFromOurA11yServicePage")
        val methodEnd = service.indexOf("private fun isOurA11yServiceDetailPage")
        val body = service.substring(methodStart, methodEnd)
        assertThat(body).contains("performGlobalAction(GLOBAL_ACTION_HOME)")
        assertThat(body).doesNotContain("launchBlockActivity")
        assertThat(body).contains("maybeTriggerSelfHealOnA11yScreen")  // keep the service armed
        assertThat(body).contains("A11Y_PAGE_PROBE_THROTTLE_MS")
        assertThat(body).contains("A11Y_PAGE_KICK_THROTTLE_MS")
    }

    @Test
    fun `eviction toast is delayed past the HOME transition and throttled`() {
        assertThat(service).contains("showPuEvictionToast()")
        assertThat(service).contains("PU_KICK_TOAST_DELAY_MS")
        assertThat(service).contains("PU_KICK_TOAST_THROTTLE_MS")
        assertThat(service).contains("pu_blocked_a11y_page_toast")
        assertThat(service).contains("Looper.getMainLooper()")
    }

    @Test
    fun `detail page detection uses the detail-only fingerprint with node probe fallback`() {
        assertThat(service).contains("isOurA11yServiceDetailPage")
        assertThat(service).contains("detailOnlyFingerprint")
        assertThat(service).contains("accessibility_service_summary")
        assertThat(service).contains("accessibility_service_description")
        // Scope restriction: only a11y-management contexts + SubSettings host.
        assertThat(service).contains("a11yContext")
        assertThat(service).contains("\"subsettings\"")
    }

    @Test
    fun `vpn settings screen is blocked via standard PU block flow inside the PU gate`() {
        val vpnIdx = service.indexOf("isVpnSettingsScreen(packageName, className)")
        assertThat(vpnIdx).isGreaterThan(-1)
        val around = service.substring(maxOf(0, vpnIdx - 1200), vpnIdx + 400)
        assertThat(around).contains("isPreventUninstallOn")
        assertThat(around).contains("launchBlockActivity(packageName, \"pu_blocked_vpn_settings_message\"")
        // The choke-point guard remains the safety net for a11y screens.
        assertThat(service).contains("Block suppressed over a11y-management screen")
    }

    @Test
    fun `pure helpers exist with the pinned semantics`() {
        assertThat(screens).contains("fun isVpnSettingsScreen(packageName: String, className: String)")
        assertThat(screens).contains("fun detailOnlyFingerprint(")
        assertThat(screens).contains("VPN_SETTINGS_CLASS_MARKER")
        // Class-only matching comment pins the no-over-blocking contract.
        assertThat(screens).contains("Network overview page")
    }

    @Test
    fun `strings for both PU screens exist in resources`() {
        val strings = File(".", "src/main/res/values/strings.xml").readText()
        assertThat(strings).contains("name=\"pu_blocked_vpn_settings_message\"")
        assertThat(strings).contains("name=\"pu_blocked_a11y_page_toast\"")
        // PU default message retained for the existing PU flows.
        assertThat(strings).contains("name=\"block_page_default_pu_message\"")
    }
}
