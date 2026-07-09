package protect.yourself.features.blockerPage.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber

/**
 * Core accessibility service — implements the actual content blocking.
 *
 * Phase 3 — full implementation:
 *  - Reads window content / URL from supported browsers
 *  - Matches against keyword blocklist + whitelist
 *  - Detects app switches + launches PornBlockActivity on block
 *  - Self-heals if killed
 *  - Implements anti-uninstall + recent apps + notification drawer blocking
 *
 * Phase 1: stub — just logs events.
 */
class MyAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // TODO Phase 3
        Timber.v("Accessibility event: ${event?.eventType}")
    }

    override fun onInterrupt() {
        Timber.w("Accessibility service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Timber.i("Accessibility service connected")
        // TODO Phase 3: load blocking config from DB
    }

    companion object {
        @Volatile
        var instance: MyAccessibilityService? = null
            private set
    }
}
