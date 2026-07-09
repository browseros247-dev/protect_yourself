package protect.yourself.features.blockerPage.identifiers

/**
 * Protective mode types (accountability partner).
 *
 * Ported from original AccountabilityPartnerTypeIdentifiers.kt.
 * Only one mode can be active at a time per user.
 */
enum class AccountabilityPartnerTypeIdentifiers(val value: Long) {
    NONE(0),
    LONG_SENTENCE(1),
    TIME_DELAY(2),
    REAL_FRIEND(3);

    companion object {
        fun fromValue(value: Long): AccountabilityPartnerTypeIdentifiers? =
            values().firstOrNull { it.value == value }

        fun fromString(s: String?): AccountabilityPartnerTypeIdentifiers {
            if (s.isNullOrBlank()) return NONE
            return fromValue(s.toLongOrNull() ?: 0L) ?: NONE
        }
    }
}
