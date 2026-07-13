package protect.yourself.features.schedulePage.identifiers

/**
 * Restriction types for scheduled app restrictions.
 *
 * - INTERNET: app's network traffic is blocked (VPN per-app-block mode)
 * - LAUNCH: app cannot be opened (Accessibility Service)
 * - BOTH: both internet and launch are blocked
 */
enum class ScheduleTypeIdentifiers(val value: String) {
    INTERNET("internet"),
    LAUNCH("launch"),
    BOTH("both");

    companion object {
        fun fromValue(value: String?): ScheduleTypeIdentifiers =
            values().firstOrNull { it.value == value } ?: INTERNET

        /** Display labels for the UI */
        fun label(value: String): String = when (value) {
            "internet" -> "Internet Blocked"
            "launch" -> "Launch Blocked"
            "both" -> "Internet + Launch Blocked"
            else -> "Unknown"
        }
    }
}
