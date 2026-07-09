package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for MyAccessibilityService companion helpers.
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
    fun `instance is initially null`() {
        assertThat(MyAccessibilityService.instance).isNull()
    }
}
