package protect.yourself.commons.utils.permissionUtils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import timber.log.Timber

/**
 * OEM-BG (v1.0.74): Helpers for OEM background-process / auto-start managers.
 *
 * Field-bug root cause (A11Y-SELFDISABLE-01 / VPN-SELFDISABLE-01): on
 * aggressive OEM builds (vivo/Funtouch, iQOO, MIUI-class, ColorOS-class,
 * EMUI-class) the system's own power/security service kills "non-autostart"
 * apps within seconds and — on several vivo builds — also SCRUBS the app's
 * entry from Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES while killing a
 * running VPN service. Symptoms observed in the field:
 *
 *  - Accessibility service turns itself OFF 1–5 s after the user enables it.
 *  - The local-DNS VPN ("DNS" toggle) silently dies; DNS blocking stops.
 *  - The kill often follows a security-relevant change such as granting or
 *    revoking device-admin ("Prevent Uninstall"), because the OEM security
 *    app re-evaluates the app right after admin-state changes.
 *
 * Nothing in our own code path disables the service (verified: all draw-over
 * windows avoid accessibility-management screens since v1.0.70, the
 * device-admin toggle flow never touches accessibility settings, and the
 * keyword engine has zero matches against our own service description).
 * Android exposes NO API to query or force an OEM auto-start whitelist, so
 * the only robust mitigation is:
 *
 *  1. Detect the device class ([isAutostartManagedDevice]).
 *  2. Deep-link the user into the OEM auto-start / background-manager screen
 *     ([openAutostartSettings]) so the app can be whitelisted manually.
 *  3. Keep the accessibility self-heal (WSS-granted lineage) and the VPN
 *     foreground reconcile (MainActivity.onResume) as runtime safety nets.
 *
 * The candidate chains below are best-effort intents well-known from OEM
 * documentation and the community-maintained "don't kill my app" dataset.
 * They are tried in order; each launch failure is logged and swallowed so a
 * missing handler never crashes the caller. The chain ALWAYS ends with the
 * universal app-details screen as a guaranteed-launchable fallback.
 */
object OemBackgroundUtils {

    private const val TAG = "OemBackgroundUtils"

    private const val PREFS_FILE = "oem_background_prefs"
    private const val KEY_AUTOSTART_HINT_ACK = "autostart_hint_acknowledged"

    /**
     * Manufacturers whose Android builds are known to police background
     * activity with an OEM-specific auto-start / background-startup manager.
     * Samsung is intentionally NOT in this set: One UI uses the standard
     * battery-optimization + sleeping-apps model which our existing
     * OB-PERM-02 battery row already covers, and its "auto run" toggles do
     * not gate accessibility or VPN survival the way vivo/MIUI/ColorOS do.
     */
    private val AUTOSTART_MANAGED_MANUFACTURERS = setOf(
        "vivo", "iqoo",
        "xiaomi", "redmi", "poco",
        "oppo", "realme", "oneplus",
        "huawei", "honor"
    )

    /** True when [manufacturer] (default: device) runs an OEM autostart manager. */
    fun isAutostartManagedDevice(manufacturer: String = Build.MANUFACTURER): Boolean =
        manufacturer.trim().lowercase() in AUTOSTART_MANAGED_MANUFACTURERS

    /** One candidate deep-link: [label] for logging, [intent] to launch. */
    data class Candidate(val label: String, val intent: Intent)

    /**
     * Ordered autostart-settings deep-links for [manufacturer]. The LAST
     * candidate is always the universal app-details screen so every chain —
     * including unknown manufacturers — is non-empty and launchable.
     */
    fun autostartCandidates(
        context: Context,
        manufacturer: String = Build.MANUFACTURER
    ): List<Candidate> {
        val oem = manufacturer.trim().lowercase()
        val pkg = context.packageName

        fun component(label: String, packageName: String, className: String): Candidate =
            Candidate(
                label,
                Intent().setClassName(packageName, className)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

        val oemChain: List<Candidate> = when (oem) {
            // vivo / iQOO (Funtouch / OriginOS): "Background startup manager".
            "vivo", "iqoo" -> listOf(
                component(
                    "vivo BgStartUpManager",
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                ),
                component(
                    "iQOO Secure",
                    "com.iqoo.secure",
                    "com.iqoo.secure.MainActivity"
                )
            )
            // Xiaomi / Redmi / POCO (MIUI / HyperOS): Security → Autostart.
            "xiaomi", "redmi", "poco" -> listOf(
                component(
                    "MIUI Autostart",
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            )
            // Oppo / Realme / OnePlus (ColorOS): Startup Manager variants.
            "oppo", "realme", "oneplus" -> listOf(
                component(
                    "ColorOS StartupAppList",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                ),
                component(
                    "ColorOS StartupAppList (legacy)",
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity"
                ),
                component(
                    "Oplus StartupAppList",
                    "com.oplus.safecenter",
                    "com.oplus.safecenter.startupapp.StartupAppListActivity"
                )
            )
            // Huawei / Honor (EMUI / MagicOS): Startup manager + protected apps.
            "huawei", "honor" -> listOf(
                component(
                    "EMUI StartupNormalAppList",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                ),
                component(
                    "EMUI ProtectedApps",
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity"
                )
            )
            else -> emptyList()
        }

        // Guaranteed-launchable fallback: our own app-details screen, where the
        // user can at least reach battery / data / manage settings.
        val appDetails = Candidate(
            "App details (fallback)",
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$pkg")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return oemChain + appDetails
    }

    /**
     * Opens the first launchable autostart-settings candidate. Every launch
     * failure (ActivityNotFoundException, SecurityException, dead package)
     * is logged and the next candidate is tried. Returns true when SOME
     * activity was started (the final fallback always should be).
     */
    fun openAutostartSettings(context: Context): Boolean {
        val candidates = autostartCandidates(context)
        for (candidate in candidates) {
            try {
                context.startActivity(candidate.intent)
                Timber.i("$TAG: opened autostart settings via '${candidate.label}'")
                return true
            } catch (t: Throwable) {
                Timber.w("$TAG: candidate '${candidate.label}' not launchable: ${t.javaClass.simpleName}")
            }
        }
        Timber.e("$TAG: no autostart-settings candidate could be launched")
        return false
    }

    // ============================================================================
    // Hint acknowledgement (persisted so onboarding/onboarding row can render granted state)
    // ============================================================================

    /** True once the user tapped the autostart row (we cannot verify the OEM toggle itself). */
    fun isAutostartHintAcknowledged(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTOSTART_HINT_ACK, false)
    } catch (t: Throwable) {
        Timber.w(t, "$TAG: ack read failed — assuming not acknowledged")
        false
    }

    /** Persist that the user was sent to the OEM autostart screen. */
    fun markAutostartHintAcknowledged(context: Context): Boolean = try {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_AUTOSTART_HINT_ACK, true).commit()
    } catch (t: Throwable) {
        Timber.w(t, "$TAG: ack write failed")
        false
    }
}
