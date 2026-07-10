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
 *  - If Throwable is present, full stack trace is captured
 *  - Tag is taken from Timber.tag() or the calling class name (Timber default)
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

        try {
            if (t != null) {
                crashLogger.logThrowable(
                    throwable = t,
                    severity = severity,
                    tag = tag ?: "",
                    message = message
                )
            } else if (message.isNotBlank()) {
                crashLogger.logMessage(
                    severity = severity,
                    tag = tag ?: "",
                    message = message
                )
            }
        } catch (_: Throwable) {
            // Never let the crash logger itself cause a crash
        }
    }
}
