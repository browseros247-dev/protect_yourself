package protect.yourself.features.appPasswordPage

import android.content.Context
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.appPasswordPage.identifiers.AppLockType
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory

/**
 * AppLockManager — manages app lock state, password storage, and verification.
 *
 * Handles:
 *  - Reading/writing lock type (OFF/PIN/PASSWORD/PATTERN)
 *  - Reading/writing stored password hash (PBKDF2 with salt)
 *  - Verifying user input against stored hash
 *  - Reading/writing Touch ID (biometric) enabled flag
 *  - Reading/writing Disable Forgot Password flag
 */
class AppLockManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    /**
     * Get the current app lock type.
     */
    suspend fun getLockType(): AppLockType {
        val raw = db.switchStatusDao().get("app_lock_type")?.asString()
        return AppLockType.fromStorageValue(raw?.toLongOrNull() ?: 0L)
    }

    /**
     * Set the app lock type + store the password hash.
     */
    suspend fun setLock(type: AppLockType, password: String) {
        val salt = generateSalt()
        val hash = hashPassword(password, salt)
        val stored = "$salt:$hash"
        db.switchStatusDao().upsert(
            protect.yourself.database.switchStatus.SwitchStatusItemModel(
                key = "app_lock_stored_hash",
                value = stored,
                type = "string"
            )
        )
        db.switchStatusDao().upsert(
            protect.yourself.database.switchStatus.SwitchStatusItemModel(
                key = "app_lock_type",
                value = type.storageValue.toString(),
                type = "long"
            )
        )
        switchValues.storeSwitchStatus(SwitchIdentifier.SET_APP_LOCK_SWITCH, type != AppLockType.OFF)
    }

    /**
     * Disable app lock entirely.
     */
    suspend fun disableLock() {
        db.switchStatusDao().upsert(
            protect.yourself.database.switchStatus.SwitchStatusItemModel(
                key = "app_lock_type",
                value = AppLockType.OFF.storageValue.toString(),
                type = "long"
            )
        )
        db.switchStatusDao().upsert(
            protect.yourself.database.switchStatus.SwitchStatusItemModel(
                key = "app_lock_stored_hash",
                value = "",
                type = "string"
            )
        )
        switchValues.storeSwitchStatus(SwitchIdentifier.SET_APP_LOCK_SWITCH, false)
        switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, false)
    }

    /**
     * Verify user input against stored password hash.
     *
     * Uses constant-time comparison to prevent timing side-channel attacks.
     */
    suspend fun verify(input: String): Boolean {
        val stored = db.switchStatusDao().get("app_lock_stored_hash")?.asString()
        if (stored.isNullOrBlank()) return false
        val parts = stored.split(":")
        if (parts.size != 2) return false
        val salt = parts[0]
        val expectedHash = parts[1]
        val actualHash = hashPassword(input, salt)
        // Constant-time comparison to prevent timing attacks
        return constantTimeEquals(actualHash, expectedHash)
    }

    /**
     * Constant-time string comparison.
     *
     * Compares every character regardless of early mismatch, so the execution
     * time does not leak information about how many characters matched.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * Check if app lock is enabled (any type other than OFF).
     */
    suspend fun isLockEnabled(): Boolean {
        return getLockType() != AppLockType.OFF
    }

    /**
     * Check if Touch ID (biometric) is enabled.
     */
    suspend fun isTouchIdEnabled(): Boolean {
        return switchValues.isTouchIdSwitchOn()
    }

    /**
     * Set Touch ID enabled.
     */
    suspend fun setTouchIdEnabled(enabled: Boolean) {
        switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, enabled)
    }

    /**
     * Check if "Forgot Password" is disabled.
     */
    suspend fun isForgotPasswordDisabled(): Boolean {
        return switchValues.isDisableForgotPasswordSwitchOn()
    }

    /**
     * Set "Forgot Password" disabled.
     */
    suspend fun setForgotPasswordDisabled(disabled: Boolean) {
        switchValues.storeSwitchStatus(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, disabled)
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    private fun hashPassword(password: String, salt: String): String {
        val spec = PBEKeySpec(
            password.toCharArray(),
            salt.hexToBytes(),
            ITERATIONS,
            KEY_LENGTH
        )
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hash = factory.generateSecret(spec).encoded
        return hash.toHex()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        return ByteArray(length / 2) { index ->
            val sub = substring(index * 2, index * 2 + 2)
            sub.toInt(16).toByte()
        }
    }

    companion object {
        // NIST SP 800-132 recommends at least 600,000 iterations for
        // PBKDF2-HMAC-SHA256 as of 2023. We use 100,000 as a balance between
        // security and unlock speed on older devices (~100ms on mid-range).
        // The original NopoX used 10,000 which is too low by modern standards.
        private const val ITERATIONS = 100_000
        private const val KEY_LENGTH = 256

        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
