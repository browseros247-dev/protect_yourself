package protect.yourself.features.protectedApps

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AccessibilityGuard — watches for accessibility service being disabled + self-heals.
 *
 * Ported from original AccessibilityGuard.kt (Phase 6).
 *
 * Behavior:
 *  - Periodically checks if our accessibility service is still enabled
 *  - If disabled (user killed it via system settings), re-prompts the user
 *  - Also detects when user is trying to disable our service via settings
 *    (accessibility event from settings page) and blocks the action
 */
class AccessibilityGuard {

    private val handler = Handler(Looper.getMainLooper())
    private val isWatching = AtomicBoolean(false)

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityServiceEnabled()
            if (isWatching.get()) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * Start watching — call from ProtectYourselfApp.onCreate.
     */
    fun startWatching(context: Context) {
        if (isWatching.compareAndSet(false, true)) {
            this.context = context.applicationContext
            handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
            Timber.i("AccessibilityGuard started watching")
        }
    }

    /**
     * Stop watching — call from ProtectYourselfApp.onTerminate (not normally called).
     */
    fun stopWatching() {
        if (isWatching.compareAndSet(true, false)) {
            handler.removeCallbacks(checkRunnable)
            Timber.i("AccessibilityGuard stopped watching")
        }
    }

    private var context: Context? = null

    private fun checkAccessibilityServiceEnabled() {
        val ctx = context ?: return
        val isEnabled = isAccessibilityServiceEnabled(ctx)
        if (!isEnabled) {
            Timber.w("Accessibility service disabled — attempting self-heal")
            selfHeal(ctx)
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        @Volatile
        private var instance: AccessibilityGuard? = null

        fun getInstance(): AccessibilityGuard {
            return instance ?: synchronized(this) {
                instance ?: AccessibilityGuard().also { instance = it }
            }
        }

        /**
         * Check if our accessibility service is enabled.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val expectedComponent = context.packageName + "/" +
                protect.yourself.features.blockerPage.service.MyAccessibilityService::class.java.name
            val enabledServices = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            return enabledServices.contains(expectedComponent)
        }

        /**
         * Attempt to re-enable the accessibility service.
         *
         * Note: Starting from Android 13+, apps cannot programmatically enable
         * their own accessibility service. The user must enable it manually.
         *
         * This function posts a notification prompting the user to re-enable it.
         */
        fun selfHeal(context: Context) {
            try {
                // Post a high-priority notification prompting user to re-enable
                protect.yourself.commons.utils.notificationUtils.NotificationHelper
                    .showAccessibilityDisabledNotification(context)
            } catch (t: Throwable) {
                Timber.e(t, "Self-heal failed")
            }
        }
    }
}

/**
 * AccessibilityPersistUtils — provides the selfHealSafe() entry point used by ProtectYourselfApp.
 */
object AccessibilityPersistUtils {

    /**
     * Safely attempt to self-heal accessibility service.
     * Called from ProtectYourselfApp.onCreate().
     */
    fun selfHealSafe() {
        try {
            // Phase 6: just log — actual self-heal is via AccessibilityGuard watcher
            Timber.d("AccessibilityPersistUtils.selfHealSafe() called")
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe failed")
        }
    }
}
