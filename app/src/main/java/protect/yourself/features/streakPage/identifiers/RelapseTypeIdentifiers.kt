package protect.yourself.features.streakPage.identifiers

/**
 * Relapse type identifiers — used in streak_dates_table.type column.
 *
 * Ported from original RelapseTypeIdentifiers.
 */
enum class RelapseTypeIdentifiers(val storageValue: String) {
    URGE("URGE"),
    BOREDOM("BOREDOM"),
    STRESS("STRESS"),
    ACCIDENTAL("ACCIDENTAL"),
    SOCIAL_MEDIA("SOCIAL_MEDIA"),
    PORN("PORN"),
    OTHER("OTHER");

    companion object {
        fun fromStorageValue(value: String?): RelapseTypeIdentifiers? =
            values().firstOrNull { it.storageValue == value }

        fun getDisplayName(type: RelapseTypeIdentifiers): String = when (type) {
            URGE -> "Urge"
            BOREDOM -> "Boredom"
            STRESS -> "Stress"
            ACCIDENTAL -> "Accidental"
            SOCIAL_MEDIA -> "Social media"
            PORN -> "Porn"
            OTHER -> "Other"
        }
    }
}

/**
 * Streak achievement milestone identifiers.
 */
enum class StreakAchievement(val daysRequired: Int, val title: String) {
    DAY_1(1, "First Day"),
    DAY_3(3, "3 Days"),
    DAY_7(7, "1 Week"),
    DAY_14(14, "2 Weeks"),
    DAY_30(30, "1 Month"),
    DAY_60(60, "2 Months"),
    DAY_90(90, "3 Months"),
    DAY_180(180, "6 Months"),
    DAY_365(365, "1 Year");

    companion object {
        fun forDayCount(days: Int): StreakAchievement? =
            values().lastOrNull { it.daysRequired <= days }
    }
}
