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
}
