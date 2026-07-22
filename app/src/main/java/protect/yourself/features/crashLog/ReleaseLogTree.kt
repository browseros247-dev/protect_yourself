package protect.yourself.features.crashLog

import android.util.Log
import timber.log.Timber

/**
 * ReleaseLogTree — Timber tree for RELEASE builds (LOG-SPAM-01, v1.0.71).
 *
 * The app previously planted `Timber.DebugTree()` in **all** builds, so every
 * VERBOSE/DEBUG statement (per-accessibility-event page text, full URLs via
 * the PB-03 duplicate guard, window-state dumps) ended up in release logcat:
 *
 *  - **Privacy**: visited URLs and page text were readable by any tooling
 *    attached to the device (the field crash export contained full browsing
 *    history in logcat tails).
 *  - **Performance**: the string templates ran on the main-thread hot path
 *    of `onAccessibilityEvent`.
 *
 * In release builds this tree forwards only **INFO and above** (lifecycle,
 * config refreshes, block decisions) — enough for the CrashLogger logcat
 * tail to stay diagnostic — and drops VERBOSE/DEBUG entirely. WARN/ERROR
 * additionally reach CrashLogger via the separately-planted
 * [CrashLoggingTree].
 *
 * (The hottest per-event log sites are additionally guarded with
 * `if (BuildConfig.DEBUG)` at the call site so their string templates are
 * not even built in release — see MyAccessibilityService.)
 */
class ReleaseLogTree : Timber.Tree() {

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (priority < Log.INFO) return
        val safeTag = tag ?: DEFAULT_TAG
        try {
            Log.println(priority, safeTag, message)
            if (t != null && priority >= Log.WARN) {
                Log.println(priority, safeTag, Log.getStackTraceString(t))
            }
        } catch (_: Throwable) {
            // Logcat itself unavailable — nothing more we can do.
        }
    }

    private companion object {
        private const val DEFAULT_TAG = "ProtectYourself"
    }
}
