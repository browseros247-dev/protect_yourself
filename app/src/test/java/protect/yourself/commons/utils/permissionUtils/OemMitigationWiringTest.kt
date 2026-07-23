package protect.yourself.commons.utils.permissionUtils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * A11Y-SELFDISABLE-01 / VPN-RESUME-01 / OEM-BG (v1.0.74) static pins —
 * wiring between the field-bug root cause (OEM background-process policing
 * on vivo/MIUI/ColorOS/EMUI-class builds) and the mitigations:
 *
 *  1. Onboarding carries a RECOMMENDED BACKGROUND_AUTOSTART row (managed
 *     devices only) whose action deep-links into the OEM autostart manager
 *     and persists the acknowledgement.
 *  2. MainActivity.onResume reconciles VPN state on every foreground return
 *     via the same idempotent restore used by the boot path.
 *  3. The accessibility warning banner offers the OEM autostart fix when
 *     the device is autostart-managed (re-enabling alone is a loop there).
 */
class OemMitigationWiringTest {

    private val mainJava = File(".", "src/main/java")

    private val onboarding: String by lazy {
        File(mainJava, "protect/yourself/commons/utils/permissionUtils/OnboardingPermissions.kt").readText()
    }
    private val oemUtils: String by lazy {
        File(mainJava, "protect/yourself/commons/utils/permissionUtils/OemBackgroundUtils.kt").readText()
    }
    private val mainActivity: String by lazy {
        File(mainJava, "protect/yourself/features/mainActivityPage/MainActivity.kt").readText()
    }
    private val blockerHome: String by lazy {
        File(mainJava, "protect/yourself/features/blockerPage/components/BlockerPageHome.kt").readText()
    }

    @Test
    fun `onboarding has the autostart kind row and evaluates it from live state`() {
        assertThat(onboarding).contains("BACKGROUND_AUTOSTART")
        assertThat(onboarding).contains("autostartApplicable: Boolean = false")
        assertThat(onboarding).contains("autostartAcknowledged: Boolean = false")
        assertThat(onboarding).contains("OemBackgroundUtils.isAutostartManagedDevice()")
        assertThat(onboarding).contains("OemBackgroundUtils.isAutostartHintAcknowledged(context)")
        // Row is gated on applicability (omitted on unmanaged devices).
        assertThat(onboarding).contains("if (autostartApplicable)")
    }

    @Test
    fun `main activity dispatches autostart row to the OEM deep-link with ack persistence`() {
        assertThat(mainActivity).contains("OnboardingPermissions.Kind.BACKGROUND_AUTOSTART ->")
        assertThat(mainActivity).contains("OemBackgroundUtils")
        assertThat(mainActivity).contains("openAutostartSettings(context)")
        assertThat(mainActivity).contains("markAutostartHintAcknowledged(context)")
        // Row has a label so its OutlinedButton renders.
        assertThat(mainActivity).contains("OnboardingPermissions.Kind.BACKGROUND_AUTOSTART -> \"Open\"")
    }

    @Test
    fun `vpn foreground reconcile is wired into onResume and uses the boot restore path`() {
        assertThat(mainActivity).contains("reconcileVpnOnForeground()")
        assertThat(mainActivity).contains("\"foreground_resume\"")
        assertThat(mainActivity).contains("VpnRestoreHelper")
        assertThat(mainActivity).contains("restoreIfEnabled")
        // Must run off the main thread (restore touches DB + VpnService.prepare).
        assertThat(mainActivity).contains("Dispatchers.IO")
    }

    @Test
    fun `accessibility warning banner offers OEM autostart fix on managed devices`() {
        assertThat(blockerHome).contains("OemBackgroundUtils")
        assertThat(blockerHome).contains("isAutostartManagedDevice()")
        assertThat(blockerHome).contains("openAutostartSettings(context)")
        assertThat(blockerHome).contains("Fix auto-kill (OEM background setting)")
    }

    @Test
    fun `oem utility pins - managed set chains fallback and no-crash contract`() {
        // vivo BgStartUpManager is the primary deep-link for the field device class.
        assertThat(oemUtils).contains("com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        assertThat(oemUtils).contains("AutoStartManagementActivity")
        assertThat(oemUtils).contains("ACTION_APPLICATION_DETAILS_SETTINGS")
        assertThat(oemUtils).contains("oem_background_prefs")
        assertThat(oemUtils).contains("autostart_hint_acknowledged")
        // Every launch failure must be swallowed (logged) — never crash onboarding/banner.
        assertThat(oemUtils).contains("catch (t: Throwable)")
    }
}
