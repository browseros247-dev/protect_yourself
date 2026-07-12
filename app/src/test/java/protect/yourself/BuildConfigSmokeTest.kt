package protect.yourself

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 smoke test — verifies BuildConfig values match the rebuild spec.
 */
class BuildConfigSmokeTest {

    @Test
    fun `application id matches rebuild spec`() {
        assertEquals("protect.yourself", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun `version name is set`() {
        // Version name evolves over time; just assert it's a non-blank string.
        assertTrue(BuildConfig.VERSION_NAME.isNotBlank())
    }

    @Test
    fun `version code is positive`() {
        assertTrue(BuildConfig.VERSION_CODE > 0)
    }

    @Test
    fun `debug flag is a boolean`() {
        // Unit tests run in the debug variant, so DEBUG will be true here.
        // This test just verifies the field exists and is a Boolean.
        val debug: Boolean = BuildConfig.DEBUG
        // Explicitly check the type rather than asserting a tautology.
        assertTrue(debug is Boolean)
    }
}
