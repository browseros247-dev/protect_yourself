package protect.yourself.features.appPasswordPage

import android.content.Context
import timber.log.Timber
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.appPasswordPage.identifiers.AppLockType
import javax.crypto.spec.PBEKeySpec
import javax.crypto.SecretKeyFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AppLockManager — manages app lock state, password storage, and verification.
 *
 * Handles:
 *  - Reading/writing lock type (OFF/PIN/PASSWORD/PATTERN)
 *  - Reading/writing stored password hash (PBKDF2 with salt)
 *  - Verifying user input against stored hash
 *  - Reading/writing Touch ID (biometric) enabled flag
 *  - Reading/writing Disable Forgot Password flag
 *  - Rate limiting failed unlock attempts (BUG-22 fix)
 */
class AppLockManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    /**
     * Get the current app lock type.
     */
    suspend fun getLockType(): AppLockType {
        return try {
            val raw = db.switchStatusDao().get("app_lock_type")?.asString()
            AppLockType.fromStorageValue(raw?.toLongOrNull() ?: 0L)
        } catch (t: Throwable) {
            Timber.e(t, "getLockType failed — defaulting to OFF")
            AppLockType.OFF
        }
    }

    /**
     * Set the app lock type + store the password hash.
     *
     * BUG-02 fix: hashPassword runs on Dispatchers.Default (CPU-bound, ~100ms).
     */
    suspend fun setLock(type: AppLockType, password: String) {
        try {
            // BUG-02 fix: PBKDF2 is CPU-bound (~100ms) — must NOT run on main thread.
            val stored = withContext(Dispatchers.Default) {
                val salt = generateSalt()
                val hash = hashPassword(password, salt)
                "$salt:$hash"
            }
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
            // BUG-22 fix: reset rate limiter on successful lock change
            resetRateLimiter()
            Timber.i("App lock set to $type")
        } catch (t: Throwable) {
            Timber.e(t, "setLock failed")
            throw t
        }
    }

    /**
     * Disable app lock entirely.
     */
    suspend fun disableLock() {
        try {
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
            // BUG-23 fix: also clear DISABLE_FORGOT_PASSWORD when lock is disabled
            switchValues.storeSwitchStatus(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, false)
            // BUG-22 fix: reset rate limiter
            resetRateLimiter()
            Timber.i("App lock disabled")
        } catch (t: Throwable) {
            Timber.e(t, "disableLock failed")
            throw t
        }
    }

    /**
     * Verify user input against stored password hash.
     *
     * Uses constant-time comparison to prevent timing side-channel attacks.
     *
     * BUG-02 fix: hashPassword runs on Dispatchers.Default (CPU-bound, ~100ms).
     * BUG-22 fix: rate limiting with exponential backoff after 5 failed attempts,
     *   lockout after 10 failed attempts.
     *
     * @return true if the password matches AND the rate limiter allows the attempt.
     *   Returns false if the password is wrong, the rate limiter is active, or
     *   any error occurs.
     */
    suspend fun verify(input: String): Boolean {
        // BUG-22 fix: check rate limiter FIRST
        if (isLockedOut()) {
            val remainingMs = getLockoutRemainingMs()
            Timber.w("verify() rejected — locked out for ${remainingMs}ms " +
                "(failedAttempts=$failedAttempts)")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "AppLock",
                "verify rejected — locked out for ${remainingMs}ms (attempts=$failedAttempts)"
            )
            return false
        }

        return try {
            val stored = db.switchStatusDao().get("app_lock_stored_hash")?.asString()
            if (stored.isNullOrBlank()) return false
            val parts = stored.split(":")
            if (parts.size != 2) return false
            val salt = parts[0]
            val expectedHash = parts[1]
            // CRASH FIX: validate salt is valid hex before calling hexToBytes().
            if (!salt.matches(Regex("^[0-9a-fA-F]+$")) || salt.length % 2 != 0) {
                Timber.e("Corrupted salt in app_lock_stored_hash — returning false")
                return false
            }

            // BUG-02 fix: PBKDF2 is CPU-bound (~100ms) — must NOT run on main thread.
            val actualHash = withContext(Dispatchers.Default) {
                hashPassword(input, salt)
            }

            val match = constantTimeEquals(actualHash, expectedHash)

            // BUG-22 fix: update rate limiter based on result
            if (match) {
                resetRateLimiter()
            } else {
                recordFailedAttempt()
            }

            match
        } catch (t: Throwable) {
            Timber.e(t, "verify() failed — returning false to prevent crash loop")
            false
        }
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

    // ===== BUG-22 fix: Rate limiting =====

    /**
     * Rate limiter state — persisted in SharedPreferences so it survives app
     * restarts (prevents brute-force via app kill + relaunch).
     *
     * Strategy:
     *   - Attempts 1-4: no delay
     *   - Attempts 5-9: exponential backoff (1s, 2s, 4s, 8s, 16s)
     *   - Attempt 10+: lockout for 5 minutes
     *   - Successful unlock resets the counter
     */
    private val ratePrefs by lazy {
        context.applicationContext.getSharedPreferences("app_lock_rate_limiter", 0)
    }

    @Volatile
    private var failedAttempts: Int = ratePrefs.getInt(KEY_FAILED_ATTEMPTS, 0)

    @Volatile
    private var lockoutUntilMs: Long = ratePrefs.getLong(KEY_LOCKOUT_UNTIL_MS, 0L)

    private fun recordFailedAttempt() {
        failedAttempts++
        if (failedAttempts >= MAX_ATTEMPTS_BEFORE_LOCKOUT) {
            // Lockout for LOCKOUT_DURATION_MS (5 minutes)
            lockoutUntilMs = System.currentTimeMillis() + LOCKOUT_DURATION_MS
            Timber.w("App lock: lockout triggered after $failedAttempts failed attempts " +
                "for ${LOCKOUT_DURATION_MS}ms")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "AppLock",
                "Lockout triggered: attempts=$failedAttempts duration=${LOCKOUT_DURATION_MS}ms"
            )
        } else if (failedAttempts >= BACKOFF_THRESHOLD) {
            // Exponential backoff: 2^(attempts - BACKOFF_THRESHOLD) seconds
            val backoffMs = (1L shl (failedAttempts - BACKOFF_THRESHOLD)) * 1000L
            lockoutUntilMs = System.currentTimeMillis() + backoffMs
            Timber.w("App lock: backoff triggered after $failedAttempts failed attempts " +
                "for ${backoffMs}ms")
        }
        // Persist
        try {
            ratePrefs.edit()
                .putInt(KEY_FAILED_ATTEMPTS, failedAttempts)
                .putLong(KEY_LOCKOUT_UNTIL_MS, lockoutUntilMs)
                .apply()
        } catch (t: Throwable) {
            Timber.w(t, "Failed to persist rate limiter state")
        }
    }

    private fun resetRateLimiter() {
        if (failedAttempts != 0 || lockoutUntilMs != 0L) {
            failedAttempts = 0
            lockoutUntilMs = 0L
            try {
                ratePrefs.edit().clear().apply()
            } catch (_: Throwable) {}
            Timber.d("App lock: rate limiter reset")
        }
    }

    /**
     * Is the user currently locked out (too many failed attempts)?
     */
    fun isLockedOut(): Boolean {
        return lockoutUntilMs > 0L && System.currentTimeMillis() < lockoutUntilMs
    }

    /**
     * Milliseconds remaining in the current lockout. 0 if not locked out.
     */
    fun getLockoutRemainingMs(): Long {
        if (!isLockedOut()) return 0L
        return lockoutUntilMs - System.currentTimeMillis()
    }

    /**
     * Current failed attempt count (for UI display).
     */
    fun getFailedAttempts(): Int = failedAttempts

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
        return try {
            switchValues.isTouchIdSwitchOn()
        } catch (t: Throwable) {
            Timber.w(t, "isTouchIdEnabled failed — defaulting to false")
            false
        }
    }

    /**
     * Set Touch ID enabled.
     */
    suspend fun setTouchIdEnabled(enabled: Boolean) {
        try {
            switchValues.storeSwitchStatus(SwitchIdentifier.TOUCH_ID_SWITCH, enabled)
        } catch (t: Throwable) {
            Timber.e(t, "setTouchIdEnabled failed")
            throw t
        }
    }

    /**
     * Check if "Forgot Password" is disabled.
     */
    suspend fun isForgotPasswordDisabled(): Boolean {
        return try {
            switchValues.isDisableForgotPasswordSwitchOn()
        } catch (t: Throwable) {
            Timber.w(t, "isForgotPasswordDisabled failed — defaulting to false")
            false
        }
    }

    /**
     * Set "Forgot Password" disabled.
     */
    suspend fun setForgotPasswordDisabled(disabled: Boolean) {
        try {
            switchValues.storeSwitchStatus(SwitchIdentifier.DISABLE_FORGOT_PASSWORD_SWITCH, disabled)
        } catch (t: Throwable) {
            Timber.e(t, "setForgotPasswordDisabled failed")
            throw t
        }
    }

    private fun generateSalt(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.toHex()
    }

    /**
     * PBKDF2-HMAC-SHA256 password hashing.
     *
     * CPU-bound (~100ms for 100K iterations). Callers MUST run this on
     * Dispatchers.Default — see BUG-02 fix.
     */
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

        // BUG-22 fix: rate limiting constants
        private const val KEY_FAILED_ATTEMPTS = "failed_attempts"
        private const val KEY_LOCKOUT_UNTIL_MS = "lockout_until_ms"
        private const val BACKOFF_THRESHOLD = 5          // attempts 5-9: exponential backoff
        private const val MAX_ATTEMPTS_BEFORE_LOCKOUT = 10  // attempt 10+: full lockout
        private const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L  // 5 minutes

        @Volatile
        private var instance: AppLockManager? = null

        fun getInstance(context: Context): AppLockManager {
            return instance ?: synchronized(this) {
                instance ?: AppLockManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
