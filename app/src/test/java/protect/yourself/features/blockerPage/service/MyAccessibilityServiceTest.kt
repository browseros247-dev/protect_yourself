package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for MyAccessibilityService companion helpers.
 *
 * PB-04 fix (v1.0.55): expanded to verify the EXTRA_MATCHED_KEYWORD constant
 * used by the block screen to display the matched keyword to the user.
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
}
