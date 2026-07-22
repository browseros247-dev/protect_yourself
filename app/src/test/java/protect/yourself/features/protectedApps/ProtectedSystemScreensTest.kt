package protect.yourself.features.protectedApps

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [ProtectedSystemScreens] (A11Y-KILL-01, v1.0.70).
 *
 * These pin the boundary between "screens our block engine must never
 * cover" (accessibility-management screens — covering them makes Android
 * DISABLE our a11y service within seconds) and "screens protect-yourself
 * legitimately blocks" (App-Info page, uninstaller dialog, device-admin
 * page, user-matched settings pages by title).
 */
class ProtectedSystemScreensTest {

    // ============================================================================
    // Settings package detection
    // ============================================================================

    @Test
    fun `settings package detection - AOSP + OEM variants + substring rule`() {
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.android.settings")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.miui.securitycenter")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.samsung.android.settings")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.huawei.systemmanager")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.coloros.safecenter")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.oppo.safe")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.oppo.settings.overlay")).isTrue()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.chrome.browser")).isFalse()
        assertThat(ProtectedSystemScreens.isSettingsPackage("protect.yourself")).isFalse()
        assertThat(ProtectedSystemScreens.isSettingsPackage("com.android.packageinstaller")).isFalse()
    }

    // ============================================================================
    // Accessibility-management screens — must NEVER be covered by a block UI
    // ============================================================================

    @Test
    fun `protected - AOSP accessibility manage screens`() {
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.Settings\$AccessibilitySettingsActivity"
            )
        ).isTrue()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.Settings\$AccessibilityInstalledServicesActivity"
            )
        ).isTrue()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings",
                "com.android.settings.accessibility.ToggleAccessibilityServicePreferenceFragment"
            )
        ).isTrue()
    }

    @Test
    fun `protected - OEM a11y screens in settings and security packages`() {
        // Samsung's a11y UI lives in its own package but under settings-family hosts
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.samsung.android.settings",
                "com.samsung.android.settings.accessibility.SecAccessibilitySettingsActivity"
            )
        ).isTrue()
        // MIUI security center a11y page
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.miui.securitycenter",
                "com.miui.securitycenter.accessibility.AccessibilitySettingsActivity"
            )
        ).isTrue()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.accessibility.HwAccessibilitySettingsActivity"
            )
        ).isTrue()
    }

    @Test
    fun `protected - installed-services and service-details marker classes`() {
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.InstalledServicesListActivity"
            )
        ).isTrue()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.ServiceDetailsActivity"
            )
        ).isTrue()
    }

    // ============================================================================
    // NOT protected — legitimate block targets / unrelated windows
    // ============================================================================

    @Test
    fun `not protected - App-Info page stays blockable (prevent-uninstall core feature)`() {
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.applications.InstalledAppDetails"
            )
        ).isFalse()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.Settings\$AppInfoDashboardActivity"
            )
        ).isFalse()
        // SubSettings is ambiguous (hosts AppInfo AND a11y detail on different
        // OS versions) — the class-name layer must NOT blanket-exempt it; the
        // service's description-fingerprint layer handles the a11y case.
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.SubSettings"
            )
        ).isFalse()
    }

    @Test
    fun `not protected - uninstaller dialog stays blockable`() {
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity"
            )
        ).isFalse()
        // Even if a misbehaving OEM nests it under a settings-looking package,
        // the uninstaller exemption wins.
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.UninstallerActivity"
            )
        ).isFalse()
    }

    @Test
    fun `not protected - device admin management stays blockable (anti-uninstall feature)`() {
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.DeviceAdminAdd"
            )
        ).isFalse()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.settings", "com.android.settings.Settings\$DeviceAdminSettingsActivity"
            )
        ).isFalse()
    }

    @Test
    fun `not protected - blank class, non-settings package, ordinary content apps`() {
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen("com.android.settings", "")
        ).isFalse()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.chrome.browser", "org.chromium.chrome.browser.accessibility.AccessibilityActivity"
            )
        ).isFalse()
        assertThat(
            ProtectedSystemScreens.isAccessibilityManagementScreen(
                "com.android.systemui", "com.android.systemui.accessibility.SomeScreen"
            )
        ).isFalse()
    }

    // ============================================================================
    // Service-description fingerprint (second layer for generic hosts)
    // ============================================================================

    @Test
    fun `service page fingerprint - matches normalized page text, tolerates truncation`() {
        val description = "Protect Yourself uses the accessibility API to block " +
            "adult content, apps and settings on this device."
        val descNorm = ProtectedSystemScreens.normalize(description)
        // Page text = title + description start, spaces and case vary on screen.
        val pageText = ProtectedSystemScreens.normalize(
            "Protect Yourself uses the accessibility api  to block adult content"
        )
        assertThat(
            ProtectedSystemScreens.pageTextMatchesOurService(pageText, descNorm)
        ).isTrue()
    }

    @Test
    fun `service page fingerprint - rejects unrelated text and junk inputs`() {
        val descNorm = ProtectedSystemScreens.normalize(
            "Protect Yourself uses the accessibility API to block adult content."
        )
        assertThat(
            ProtectedSystemScreens.pageTextMatchesOurService(
                ProtectedSystemScreens.normalize("App info storage permissions uninstall"),
                descNorm
            )
        ).isFalse()
        assertThat(ProtectedSystemScreens.pageTextMatchesOurService("anything", "short")).isFalse()
        assertThat(ProtectedSystemScreens.pageTextMatchesOurService("", descNorm)).isFalse()
    }

    @Test
    fun `normalize - lowercases and strips spaces`() {
        assertThat(ProtectedSystemScreens.normalize("App Info  SETTINGS")).isEqualTo("appinfosettings")
    }
}
