package protect.yourself.commons.utils

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * OncePerSessionLogger — utility to ensure a specific warning/error message
 * is only logged ONCE per app session, preventing log spam when the same
 * condition triggers repeatedly.
 *
 * # Why this exists
 *
 * Crash log analysis (v1.0.46, vivo V2206) showed a repeated
 * permission/fallback warning logged **14 times in 20 minutes** — every
 * time a block was triggered. This flooded the crash log with duplicate
 * WARN entries, making it harder to spot real issues. Any condition that
 * doesn't change between log entries should be logged once per session —
 * the reader already knows from the first warning.
 *
 * # Usage
 *
 * ```kotlin
 * if (!someRequiredCapability(context)) {
 *     OncePerSessionLogger.warn(
 *         key = "capability_missing",
 *         message = "Capability X not granted — using fallback path."
 *     )
 *     // ... fall back
 * }
 * ```
 *
 * The `key` uniquely identifies the warning — subsequent calls with the
 * same key in the same session are silently dropped. The key should be
 * stable across app restarts (it's a session-level dedup, not persistent).
 *
 * # Thread safety
 *
 * Uses `ConcurrentHashMap` — safe to call from any thread.
 */
object OncePerSessionLogger {

    private val loggedKeys = ConcurrentHashMap<String, Boolean>()

    /**
     * Log a WARN message only once per session.
     *
     * @param key unique identifier for this warning. Subsequent calls with
     *   the same key are silently dropped.
     * @param message the warning message to log
     * @param t optional throwable to include in the log
     * @return true if the message was logged, false if it was deduplicated
     */
    fun warn(key: String, message: String, t: Throwable? = null): Boolean {
        if (loggedKeys.putIfAbsent(key, true) != null) {
            return false  // already logged this session
        }
        if (t != null) {
            Timber.w(t, message)
        } else {
            Timber.w(message)
        }
        return true
    }

    /**
     * Reset all dedup keys. Called automatically when the application
     * is created (each new session starts fresh).
     */
    fun resetAll() {
        loggedKeys.clear()
    }
}
