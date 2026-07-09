package protect.yourself.features.blockerPage.identifiers

/**
 * App lock types for the in-app lock screen.
 *
 * Ported from original AppLockTypeIdentifiers.kt.
 * OFF = no app lock; PIN/PASSWORD/PATTERN = unlock method.
 * Biometric (touch ID) is an optional layer on top of PIN/PASSWORD/PATTERN.
 */
enum class AppLockTypeIdentifiers(val value: Long) {
    OFF(0),
    PIN(1),
    PASSWORD(2),
    PATTERN(3);

    companion object {
        fun fromValue(value: Long): AppLockTypeIdentifiers? =
            values().firstOrNull { it.value == value }

        fun fromString(s: String?): AppLockTypeIdentifiers {
            if (s.isNullOrBlank()) return OFF
            return fromValue(s.toLongOrNull() ?: 0L) ?: OFF
        }
    }
}
