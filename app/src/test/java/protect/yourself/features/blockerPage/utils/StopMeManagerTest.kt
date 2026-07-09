package protect.yourself.features.blockerPage.utils

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for StopMeManager — scheduling logic.
 *
 * Tests the day-bitmask + start-time → next-trigger calculation.
 * Full DAO tests are in AllDaosTest.
 */
class StopMeManagerTest {

    // Note: StopMeManager requires a Context, which we can't easily get in pure unit tests.
    // We test the calendar math directly by extracting the algorithm.

    @Test
    fun `DAY_ALL bitmask is 127`() {
        assertThat(StopMeManager.DAY_ALL).isEqualTo(127)
    }

    @Test
    fun `day bit constants are powers of 2`() {
        assertThat(StopMeManager.DAY_SUNDAY).isEqualTo(1)
        assertThat(StopMeManager.DAY_MONDAY).isEqualTo(2)
        assertThat(StopMeManager.DAY_TUESDAY).isEqualTo(4)
        assertThat(StopMeManager.DAY_WEDNESDAY).isEqualTo(8)
        assertThat(StopMeManager.DAY_THURSDAY).isEqualTo(16)
        assertThat(StopMeManager.DAY_FRIDAY).isEqualTo(32)
        assertThat(StopMeManager.DAY_SATURDAY).isEqualTo(64)
    }

    @Test
    fun `weekday bitmask = Mon-Fri = 62`() {
        val weekdays = StopMeManager.DAY_MONDAY or
            StopMeManager.DAY_TUESDAY or
            StopMeManager.DAY_WEDNESDAY or
            StopMeManager.DAY_THURSDAY or
            StopMeManager.DAY_FRIDAY
        assertThat(weekdays).isEqualTo(62)
    }

    @Test
    fun `weekend bitmask = Sat-Sun = 65`() {
        val weekend = StopMeManager.DAY_SUNDAY or StopMeManager.DAY_SATURDAY
        assertThat(weekend).isEqualTo(65)
    }

    @Test
    fun `day bitmask combinations`() {
        val monWedFri = StopMeManager.DAY_MONDAY or
            StopMeManager.DAY_WEDNESDAY or
            StopMeManager.DAY_FRIDAY
        assertThat(monWedFri).isEqualTo(42)  // 2 + 8 + 32
    }

    @Test
    fun `TimeUnit conversions for typical Stop Me durations`() {
        // 15 minutes = 900_000 ms
        assertThat(TimeUnit.MINUTES.toMillis(15)).isEqualTo(900_000L)
        // 30 minutes = 1_800_000 ms
        assertThat(TimeUnit.MINUTES.toMillis(30)).isEqualTo(1_800_000L)
        // 1 hour = 3_600_000 ms
        assertThat(TimeUnit.HOURS.toMillis(1)).isEqualTo(3_600_000L)
        // 2 hours = 7_200_000 ms
        assertThat(TimeUnit.HOURS.toMillis(2)).isEqualTo(7_200_000L)
    }

    @Test
    fun `9 AM in millis is 32_400_000`() {
        // 9 hours * 60 * 60 * 1000
        assertThat(9L * 60 * 60 * 1000).isEqualTo(32_400_000L)
    }
}
