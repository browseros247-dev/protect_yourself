package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for MyAccessibilityService companion helpers.
 *
 * PB-04 fix (v1.0.55): expanded to verify the EXTRA_MATCHED_KEYWORD constant
 * used by the block screen to display the matched keyword to the user.
 *
 * SET-01/SET-02 fix: expanded to verify the settings-over-blocking fix —
 * isAnyTitleBlocked is removed, isSettingsPage takes AccessibilityEvent,
 * extractSettingsPageTitle exists.
 *
 * PU-01 fix: expanded to verify the prevent-uninstall scope fix — the
 * isAppInfoPage check now requires our app name (or accessibility description)
 * to be present, so it no longer blocks OTHER apps' info / device-admin /
 * accessibility pages.
 */
class MyAccessibilityServiceTest {

    @Test
    fun `EXTRA_BLOCK_PACKAGE constant is stable`() {
        assertThat(MyAccessibilityService.EXTRA_BLOCK_PACKAGE).isEqualTo("extra_block_package")
    }

    @Test
    fun `EXTRA_BLOCK_MESSAGE_KEY constant is stable`() {
        assertThat(MyAccessibilityService.EXTRA_BLOCK_MESSAGE_KEY).isEqualTo("extra_block_message_key")
    }

    @Test
    fun `EXTRA_MATCHED_KEYWORD constant is stable`() {
        // KB-19: extra key for the matched keyword, passed to PornBlockActivity.
        assertThat(MyAccessibilityService.EXTRA_MATCHED_KEYWORD).isEqualTo("extra_matched_keyword")
    }

    @Test
    fun `instance is initially null`() {
        assertThat(MyAccessibilityService.instance).isNull()
    }

    // ===== SET-01/SET-02 fix tests (settings over-blocking) =====

    // SET-01 fix: verify that the over-blocking isAnyTitleBlocked method
    // has been removed. The previous implementation ran on ALL apps and
    // matched keywords against entire window text, which caused the main
    // device Settings to be blocked whenever any keyword appeared anywhere
    // on screen. This test ensures the method is gone and cannot regress.
    @Test
    fun `isAnyTitleBlocked method is removed`() {
        val methods = MyAccessibilityService::class.java.declaredMethods
        val hasMethod = methods.any { it.name == "isAnyTitleBlocked" }
        assertThat(hasMethod).isFalse()
    }

    // SET-02 fix: verify that the new extractSettingsPageTitle method
    // exists. This method extracts the actual toolbar title from specific
    // Settings-app view IDs (matching NopoX 1.0.53), instead of checking
    // the entire window text.
    @Test
    fun `extractSettingsPageTitle method exists`() {
        val methods = MyAccessibilityService::class.java.declaredMethods
        val hasMethod = methods.any { it.name == "extractSettingsPageTitle" }
        assertThat(hasMethod).isTrue()
    }

    // SET-01 fix: verify that isSettingsPage now takes an AccessibilityEvent
    // parameter (for view-ID lookup) instead of a String (the entire window
    // text). The old signature was (String, String); the new signature is
    // (String, AccessibilityEvent). This enforces the fix — callers cannot
    // accidentally pass the whole window text anymore.
    @Test
    fun `isSettingsPage takes AccessibilityEvent as second param`() {
        val methods = MyAccessibilityService::class.java.declaredMethods
        val isSettingsPage = methods.firstOrNull { it.name == "isSettingsPage" }
        assertThat(isSettingsPage).isNotNull()
        val paramTypes = isSettingsPage!!.parameterTypes.map { it.name }
        // Should be exactly 2 params: (String packageName, AccessibilityEvent)
        assertThat(paramTypes).hasSize(2)
        assertThat(paramTypes[0]).isEqualTo("java.lang.String")
        assertThat(paramTypes[1]).isEqualTo("android.view.accessibility.AccessibilityEvent")
    }

    // ===== PU-01 fix tests (prevent-uninstall scope) =====
    //
    // The prevent-uninstall check (isAppInfoPage) must be SCOPED to our own
    // app. The previous implementation blocked ANY app's app-info /
    // device-admin / accessibility page because it matched on class-name
    // patterns and device-admin text WITHOUT requiring our app name to be
    // present in the page text.
    //
    // These tests verify the fix at the structural level: the method
    // signature and the presence of the scoped checks. Full behavioral
    // verification requires an instrumented test on a real device (the
    // method is private and depends on Android AccessibilityNodeInfo /
    // resources), but the structural tests guard against regression of
    // the key fix points.

    @Test
    fun `isAppInfoPage method exists with correct signature`() {
        val methods = MyAccessibilityService::class.java.declaredMethods
        val isAppInfoPage = methods.firstOrNull { it.name == "isAppInfoPage" }
        assertThat(isAppInfoPage).isNotNull()
        // Signature: (String packageName, String className, String text) -> Boolean
        val paramTypes = isAppInfoPage!!.parameterTypes.map { it.name }
        assertThat(paramTypes).hasSize(3)
        assertThat(paramTypes[0]).isEqualTo("java.lang.String")
        assertThat(paramTypes[1]).isEqualTo("java.lang.String")
        assertThat(paramTypes[2]).isEqualTo("java.lang.String")
        assertThat(isAppInfoPage.returnType.name).isEqualTo("boolean")
    }

    @Test
    fun `isAppInfoPageUnsafe method exists with correct signature`() {
        val methods = MyAccessibilityService::class.java.declaredMethods
        val isAppInfoPageUnsafe = methods.firstOrNull { it.name == "isAppInfoPageUnsafe" }
        assertThat(isAppInfoPageUnsafe).isNotNull()
        val paramTypes = isAppInfoPageUnsafe!!.parameterTypes.map { it.name }
        assertThat(paramTypes).hasSize(3)
        assertThat(isAppInfoPageUnsafe.returnType.name).isEqualTo("boolean")
    }

    @Test
    fun `isSettingsPackage method exists for OEM settings detection`() {
        // PU-01 relies on isSettingsPackage to gate the check on settings
        // packages (AOSP + OEM variants). Verify it still exists.
        val methods = MyAccessibilityService::class.java.declaredMethods
        val isSettingsPackage = methods.firstOrNull { it.name == "isSettingsPackage" }
        assertThat(isSettingsPackage).isNotNull()
        val paramTypes = isSettingsPackage!!.parameterTypes.map { it.name }
        assertThat(paramTypes).hasSize(1)
        assertThat(paramTypes[0]).isEqualTo("java.lang.String")
        assertThat(isSettingsPackage.returnType.name).isEqualTo("boolean")
    }
}
