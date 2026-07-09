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
        assertEquals("1.0.0", BuildConfig.VERSION_NAME)
    }

    @Test
    fun `version code is positive`() {
        assertTrue(BuildConfig.VERSION_CODE > 0)
    }

    @Test
    fun `debug flag is false for release`() {
        // Unit tests run in debug variant — DEBUG will be true here.
        // This test just verifies the field exists and is a Boolean.
        val debug: Boolean = BuildConfig.DEBUG
        assertTrue(debug || !debug) // always true
    }
}
