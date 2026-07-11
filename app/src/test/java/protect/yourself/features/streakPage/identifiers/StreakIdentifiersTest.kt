package protect.yourself.features.streakPage.identifiers

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for streak identifiers + achievement logic.
 */
class StreakIdentifiersTest {

    // ===== RelapseTypeIdentifiers =====

    @Test
    fun `RelapseTypeIdentifiers has 7 types`() {
        assertThat(RelapseTypeIdentifiers.values()).asList().hasSize(7)
    }

    @Test
    fun `RelapseTypeIdentifiers storageValue is uppercase`() {
        RelapseTypeIdentifiers.values().forEach { type ->
            assertThat(type.storageValue).isEqualTo(type.storageValue.uppercase())
            assertThat(type.storageValue).isNotEmpty()
        }
    }

    @Test
    fun `RelapseTypeIdentifiers fromStorageValue round-trip`() {
        RelapseTypeIdentifiers.values().forEach { type ->
            val roundTrip = RelapseTypeIdentifiers.fromStorageValue(type.storageValue)
            assertThat(roundTrip).isEqualTo(type)
        }
    }

    @Test
    fun `RelapseTypeIdentifiers fromStorageValue returns null for unknown`() {
        assertThat(RelapseTypeIdentifiers.fromStorageValue("UNKNOWN_TYPE")).isNull()
        assertThat(RelapseTypeIdentifiers.fromStorageValue(null)).isNull()
        assertThat(RelapseTypeIdentifiers.fromStorageValue("")).isNull()
    }

    @Test
    fun `getDisplayName returns human-readable name`() {
        assertThat(RelapseTypeIdentifiers.getDisplayName(RelapseTypeIdentifiers.URGE)).isEqualTo("Urge")
        assertThat(RelapseTypeIdentifiers.getDisplayName(RelapseTypeIdentifiers.BOREDOM)).isEqualTo("Boredom")
        assertThat(RelapseTypeIdentifiers.getDisplayName(RelapseTypeIdentifiers.STRESS)).isEqualTo("Stress")
        assertThat(RelapseTypeIdentifiers.getDisplayName(RelapseTypeIdentifiers.SOCIAL_MEDIA)).isEqualTo("Social media")
        assertThat(RelapseTypeIdentifiers.getDisplayName(RelapseTypeIdentifiers.PORN)).isEqualTo("Porn")
        assertThat(RelapseTypeIdentifiers.getDisplayName(RelapseTypeIdentifiers.OTHER)).isEqualTo("Other")
    }

    // ===== StreakAchievement =====

    @Test
    fun `StreakAchievement has 9 milestones`() {
        assertThat(StreakAchievement.values()).asList().hasSize(9)
    }

    @Test
    fun `StreakAchievement milestones are in ascending order`() {
        val days = StreakAchievement.values().map { it.daysRequired }
        for (i in 1 until days.size) {
            assertThat(days[i]).isGreaterThan(days[i - 1])
        }
    }

    @Test
    fun `StreakAchievement forDayCount returns correct milestone`() {
        assertThat(StreakAchievement.forDayCount(0)).isNull()
        assertThat(StreakAchievement.forDayCount(1)).isEqualTo(StreakAchievement.DAY_1)
        assertThat(StreakAchievement.forDayCount(2)).isEqualTo(StreakAchievement.DAY_1)
        assertThat(StreakAchievement.forDayCount(3)).isEqualTo(StreakAchievement.DAY_3)
        assertThat(StreakAchievement.forDayCount(6)).isEqualTo(StreakAchievement.DAY_3)
        assertThat(StreakAchievement.forDayCount(7)).isEqualTo(StreakAchievement.DAY_7)
        assertThat(StreakAchievement.forDayCount(13)).isEqualTo(StreakAchievement.DAY_7)
        assertThat(StreakAchievement.forDayCount(14)).isEqualTo(StreakAchievement.DAY_14)
        assertThat(StreakAchievement.forDayCount(29)).isEqualTo(StreakAchievement.DAY_14)
        assertThat(StreakAchievement.forDayCount(30)).isEqualTo(StreakAchievement.DAY_30)
        assertThat(StreakAchievement.forDayCount(89)).isEqualTo(StreakAchievement.DAY_60)
        assertThat(StreakAchievement.forDayCount(90)).isEqualTo(StreakAchievement.DAY_90)
        assertThat(StreakAchievement.forDayCount(179)).isEqualTo(StreakAchievement.DAY_90)
        assertThat(StreakAchievement.forDayCount(180)).isEqualTo(StreakAchievement.DAY_180)
        assertThat(StreakAchievement.forDayCount(364)).isEqualTo(StreakAchievement.DAY_180)
        assertThat(StreakAchievement.forDayCount(365)).isEqualTo(StreakAchievement.DAY_365)
        assertThat(StreakAchievement.forDayCount(1000)).isEqualTo(StreakAchievement.DAY_365)
    }

    @Test
    fun `StreakAchievement DAY_1 has correct daysRequired`() {
        assertThat(StreakAchievement.DAY_1.daysRequired).isEqualTo(1)
    }

    @Test
    fun `StreakAchievement DAY_365 has correct daysRequired`() {
        assertThat(StreakAchievement.DAY_365.daysRequired).isEqualTo(365)
    }

    @Test
    fun `StreakAchievement titles are non-empty`() {
        StreakAchievement.values().forEach { achievement ->
            assertThat(achievement.title).isNotEmpty()
        }
    }
}
