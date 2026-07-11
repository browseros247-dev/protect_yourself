package protect.yourself.features.crashLog

import android.util.Log
import timber.log.Timber

/**
 * CrashLoggingTree — Timber tree that routes WARN/ERROR logs to CrashLogger.
 *
 * Planted alongside Timber.DebugTree so logs go to both logcat AND the
 * structured crash log system.
 *
 * Behaviour:
 *  - DEBUG + INFO: skipped (too noisy, logcat is enough)
 *  - WARN: logged to CrashLogger with severity WARN
 *  - ERROR: logged to CrashLogger with severity ERROR
 *  - ASSERT: logged to CrashLogger with severity ASSERT
 *  - If Throwable is present, full stack trace is captured
 *  - Tag is taken from Timber.tag() or the calling class name (Timber default)
 *
 * # Fallback behaviour
 *
 * If `crashLogger.logThrowable` / `logMessage` itself throws (shouldn't
 * happen, but defensively), the tree falls back to writing directly to
 * logcat via `android.util.Log.e` so the original Timber log is not lost.
 * The previous version silently swallowed all exceptions in this catch
 * block, which meant a CrashLogger failure would also lose the original
 * log message.
 */
class CrashLoggingTree(private val crashLogger: CrashLogger) : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Only capture WARN+ for the crash log (DEBUG/INFO go to logcat only)
        if (priority < Log.WARN) return

        val severity = when (priority) {
            Log.WARN -> CrashSeverity.WARN
            Log.ERROR -> CrashSeverity.ERROR
            Log.ASSERT -> CrashSeverity.ASSERT
            else -> return  // DEBUG, INFO, VERBOSE — skip
        }

        val safeTag = tag ?: ""

        try {
            if (t != null) {
                crashLogger.logThrowable(
                    throwable = t,
                    severity = severity,
                    tag = safeTag,
                    message = message
                )
            } else if (message.isNotBlank()) {
                crashLogger.logMessage(
                    severity = severity,
                    tag = safeTag,
                    message = message
                )
            }
        } catch (loggerFailure: Throwable) {
            // Never let the crash logger itself cause a crash — but also
            // don't silently drop the original log. Fall back to logcat
            // so the original message + the logger failure are both visible.
            try {
                Log.e("CrashLoggingTree", "Failed to log via CrashLogger", loggerFailure)
                if (t != null) {
                    Log.e(safeTag.ifBlank { "CrashLoggingTree" }, message, t)
                } else {
                    Log.println(priority, safeTag.ifBlank { "CrashLoggingTree" }, message)
                }
            } catch (_: Throwable) {
                // Truly nothing more we can do — logcat itself is broken.
                // Swallow to avoid crashing the calling code.
            }
        }
    }
}
