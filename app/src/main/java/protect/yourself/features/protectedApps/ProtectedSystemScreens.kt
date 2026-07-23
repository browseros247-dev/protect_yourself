package protect.yourself.features.protectedApps

import java.util.Locale

/**
 * ProtectedSystemScreens — identifies Settings screens that manage the
 * **accessibility subsystem itself**, which our block UI must never cover.
 *
 * ## The kill vector (A11Y-KILL-01, v1.0.70)
 *
 * Android actively protects its accessibility-management screens against
 * obscuring: when an accessibility service draws a blocking window over the
 * a11y service detail/enable page, the system **disables that service
 * automatically** (anti-tapjacking / consent-integrity protection — a user
 * must always be able to see and control which accessibility services run).
 * Empirically the service dies ~1–5 s after the covering window appears.
 *
 * The prevent-uninstall (PU) and settings-page (SET) block checks matched
 * those exact screens:
 *   - PU "Check 4" explicitly blocked our own a11y detail page
 *     (matched by service-description text) — a guaranteed self-kill.
 *   - PU "Check 1" fired on ANY settings `SubSettings` window containing
 *     our app name — and on modern AOSP versions the a11y service detail
 *     page IS hosted by `SubSettings`, with our app name in its node tree.
 *   - SET title matching treated an "Accessibility" page title like any
 *     other keyword hit.
 *
 * Result: the user enables the service → the block screen covers the
 * service page → Android kills the service within seconds → (v1.0.69
 * self-heal re-arms → loop). Exactly the reported "turns itself off a few
 * seconds after I enable it", and the Prevent-Uninstall correlation the
 * user suspected (the PU checks are gated on the Prevent-Uninstall switch).
 *
 * ## The fix
 *
 * Every block decision passes through [isAccessibilityManagementScreen]
 * (class-name layer) and, in the service, an additional node-text layer
 * that recognizes OUR service detail page — both make the block engine
 * skip the window instead. Anti-circumvention intent is preserved via
 * the proper OS-supported channel: the v1.0.69 self-heal (re-enable if
 * the user turns us off) instead of physically obscuring the page.
 *
 * This object is deliberately pure (no Android dependencies beyond
 * [Locale]) so every rule is unit-testable.
 */
object ProtectedSystemScreens {

    /** Settings packages (AOSP + OEM variants) — mirrors MyAccessibilityService's list. */
    private val SETTINGS_PACKAGES = setOf(
        "com.android.settings",
        "com.miui.securitycenter",
        "com.android.settings.miui",
        "com.samsung.android.settings",
        "com.huawei.systemmanager",
        "com.coloros.safecenter",
        "com.oppo.safe",
        "com.iqiyi.terms",
    )

    /**
     * Class-name markers for screens that let the user manage accessibility
     * services (list pages, service detail/toggle pages, a11y settings).
     * AOSP class names always embed "accessibility"
     * (`Settings$AccessibilitySettingsActivity`,
     * `accessibility.ToggleAccessibilityServicePreferenceFragment` hosted by
     * SubSettings, etc.); Samsung (`com.samsung.accessibility.*`), MIUI,
     * Huawei, OPPO/vivo follow the same convention.
     */
    private val A11Y_MANAGE_CLASS_MARKERS = listOf(
        "accessibility",          // covers AccessibilitySettings/InstalledServices/Details… across all OEMs
        "installedservices",      // defensive: generic installed-services list hosts
        "servicedetails",         // defensive: service detail hosts
    )

    /** True for AOSP or OEM settings-app packages. */
    fun isSettingsPackage(packageName: String): Boolean {
        if (packageName in SETTINGS_PACKAGES) return true
        // Substring match for other OEM settings variants (same rule the
        // service has always used).
        if (packageName.contains(".settings")) return true
        return false
    }

    /**
     * True when `packageName`/`className` identify a screen where
     * accessibility services are listed or toggled. These screens must NEVER
     * be covered by a block UI (the OS disables the covering service).
     *
     * @param packageName window package (from the accessibility event)
     * @param className   window class (from the accessibility event; may be blank)
     * @return false for non-settings packages, blank class names, the
     *   packageinstaller confirmation dialog (UninstallerActivity is a PU
     *   blocking target — it does NOT trigger the kill, touches there are
     *   merely filtered), and ordinary App-Info pages (also PU targets).
     */
    fun isAccessibilityManagementScreen(packageName: String, className: String): Boolean {
        if (className.isBlank()) return false
        if (!isSettingsPackage(packageName)) return false
        // The uninstaller is intentionally NOT protected — blocking it is the
        // whole point of prevent-uninstall (packageinstaller windows don't
        // trigger accessibility-kill protection).
        val lower = className.lowercase(Locale.ROOT)
        if (lower.contains("uninstalleractivity")) return false
        return A11Y_MANAGE_CLASS_MARKERS.any { lower.contains(it) }
    }

    /**
     * True when arbitrary page text (lowercased, spaces stripped) contains
     * OUR accessibility service description — the fingerprint of our own
     * service detail page. Used as the second detection layer for OEM
     * variants whose host activity class name is generic (`SubSettings`).
     */
    fun pageTextMatchesOurService(pageTextNormalized: String, serviceDescriptionNormalized: String): Boolean {
        if (serviceDescriptionNormalized.length < 8) return false
        // Use a stable prefix: descriptions may be truncated on screen.
        val fingerprint = serviceDescriptionNormalized.take(40)
        return pageTextNormalized.contains(fingerprint)
    }

    /** Normalize free-form page/description text for marker matching. */
    fun normalize(text: String): String =
        text.lowercase(Locale.ROOT).replace(" ", "")

    // ============================================================================
    // PU-SETTINGS-01 (v1.0.75): PU-gated protection of system settings pages
    // ============================================================================

    /**
     * Class-name markers that uniquely identify the system VPN settings
     * screen across AOSP and OEM skins:
     *  - AOSP 12+: `com.android.settings.Settings$VpnSettingsActivity`
     *  - Android 13/14 network rework: `...network.vpn.VpnSettingsActivity`
     *  - Legacy AOSP host for `vpn2.VpnSettings`: `Settings$VpnSettingsActivity`
     *  - OEM (MIUI/vivo/ColorOS) VPN pages keep a `…Vpn…` activity class name
     *    inside their settings package.
     */
    private const val VPN_SETTINGS_CLASS_MARKER = "vpn"

    /**
     * True when [packageName]/[className] identify the system VPN settings
     * screen (the page where a VPN app can be disconnected/forgotten and
     * always-on can be toggled). Used by PU-VPN-01: while Prevent Uninstall
     * is ON, this screen gets the standard PU block screen.
     *
     * Covering this screen is SAFE — the OS kill-switch only applies to
     * accessibility-management screens. Class-only matching on purpose:
     * the event TEXT "VPN" also appears on the Network overview page, which
     * must stay reachable (only the VPN page itself is blocked).
     */
    fun isVpnSettingsScreen(packageName: String, className: String): Boolean {
        if (className.isBlank()) return false
        if (!isSettingsPackage(packageName)) return false
        return className.lowercase(Locale.ROOT).contains(VPN_SETTINGS_CLASS_MARKER)
    }

    /**
     * Derives a normalized fingerprint that appears ONLY on our accessibility
     * service's DETAIL page, never on the services LIST. The list row shows
     * [serviceSummaryNormalized] (a prefix of the description); the detail
     * page shows the full [serviceDescriptionNormalized]. The suffix that
     * follows the summary is therefore unique to the detail page.
     *
     * Returns "" when the inputs cannot yield a distinctive marker
     * (caller then fails open = no blocking, same as v1.0.74 behavior).
     */
    fun detailOnlyFingerprint(
        serviceDescriptionNormalized: String,
        serviceSummaryNormalized: String
    ): String {
        val suffix = if (serviceSummaryNormalized.length >= 8 &&
            serviceDescriptionNormalized.startsWith(serviceSummaryNormalized)
        ) {
            serviceDescriptionNormalized.removePrefix(serviceSummaryNormalized)
        } else {
            // Fallback: use the tail of the description — the summary
            // conventionally carries its beginning, not its end.
            serviceDescriptionNormalized.drop(40)
        }
        return suffix.take(40)
    }
}
