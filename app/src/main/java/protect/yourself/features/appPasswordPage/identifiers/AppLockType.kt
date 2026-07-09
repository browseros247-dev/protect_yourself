package protect.yourself.features.appPasswordPage.identifiers

/**
 * App lock types.
 * Mirrors AppLockTypeIdentifiers.kt but here as a simple enum for the lock screen UI.
 */
enum class AppLockType(val storageValue: Long) {
    OFF(0), PIN(1), PASSWORD(2), PATTERN(3);

    companion object {
        fun fromStorageValue(value: Long): AppLockType =
            values().firstOrNull { it.storageValue == value } ?: OFF
    }
}
