package protect.yourself.features.protectedApps

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AccessibilityGuard — watches for accessibility service being disabled and
 * self-heals it.
 *
 * ## Two-layer detection
 *
 *   1. **ContentObserver** (instant) — registered on
 *      `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. The millisecond
 *      Android removes our service from the list (whether by the user
 *      toggling it off in Settings, an OEM killing it for battery
 *      optimisation, or an ADB command), the observer fires on the main
 *      thread and we immediately re-arm via
 *      [AccessibilityPersistUtils.selfHealSafe] on a background thread.
 *
 *   2. **30-second polling** (fallback) — for cases where the ContentObserver
 *      doesn't fire (e.g. some MIUI builds don't deliver the change
 *      notification). The polling loop also calls `selfHealSafe`.
 *
 * ## Permission gate
 *
 * Both layers are no-ops if `WRITE_SECURE_SETTINGS` is not granted. In that
 * case the polling layer still detects the disablement and posts a
 * high-priority notification prompting the user to re-enable manually
 * (Android 13+ blocks programmatic re-enable without the permission).
 *
 * Ported + extended from the original NopoX `AccessibilityGuard.kt`.
 */
class AccessibilityGuard {

    private val handler = Handler(Looper.getMainLooper())
    private val isWatching = AtomicBoolean(false)
    private val observerRegistered = AtomicBoolean(false)

    @Volatile
    private var context: Context? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityServiceEnabled()
            if (isWatching.get()) {
                handler.postDelayed(this, CHECK_INTERVAL_MS)
            }
        }
    }

    /**
     * ContentObserver that fires the instant `enabled_accessibility_services`
     * changes. Registered via [ensureWatching] and unregistered via
     * [stopWatching]. Re-entrancy guarded by [observerRegistered].
     */
    private val servicesObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            // Spawn a background thread — never do ContentResolver I/O on main.
            val ctx = context ?: return
            Thread({
                try {
                    AccessibilityPersistUtils.selfHealSafe(ctx)
                } catch (t: Throwable) {
                    Timber.w(t, "AccessibilityGuard observer: selfHealSafe threw")
                }
            }, "A11yGuard-Rearm").start()
        }
    }

    /**
     * Start watching. Idempotent — safe to call multiple times.
     * Call from `ProtectYourselfApp.onCreate()`.
     */
    fun startWatching(context: Context) {
        if (isWatching.compareAndSet(false, true)) {
            this.context = context.applicationContext
            handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
            registerObserver(context.applicationContext)
            Timber.i("AccessibilityGuard started watching (polling + observer)")
        }
    }

    /**
     * Ensure the watcher is running. Called from every `selfHealSafe()`
     * invocation point (service connect, boot receiver, etc.) so the observer
     * is re-registered if it somehow got unregistered (e.g. the system killed
     * our process and the static state was reset).
     */
    fun ensureWatching(context: Context) {
        this.context = context.applicationContext
        if (isWatching.compareAndSet(false, true)) {
            handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        }
        registerObserver(context.applicationContext)
    }

    /**
     * Stop watching. Only call from `Application.onTerminate()` (which
     * Android rarely calls in practice).
     */
    fun stopWatching() {
        if (isWatching.compareAndSet(true, false)) {
            handler.removeCallbacks(checkRunnable)
        }
        unregisterObserver()
    }

    private fun registerObserver(context: Context) {
        if (!observerRegistered.compareAndSet(false, true)) return
        try {
            val cr = context.contentResolver
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                servicesObserver
            )
            Timber.i("AccessibilityGuard: ContentObserver registered")
        } catch (t: Throwable) {
            observerRegistered.set(false)
            Timber.w(t, "AccessibilityGuard: failed to register ContentObserver")
        }
    }

    private fun unregisterObserver() {
        if (!observerRegistered.compareAndSet(true, false)) return
        try {
            context?.contentResolver?.unregisterContentObserver(servicesObserver)
        } catch (_: Throwable) {}
    }

    private fun checkAccessibilityServiceEnabled() {
        val ctx = context ?: return
        val isEnabled = isAccessibilityServiceEnabled(ctx)
        if (!isEnabled) {
            Timber.w("AccessibilityGuard: service disabled — attempting self-heal")
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
         * Check if our accessibility service is enabled in
         * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`.
         */
        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            return AccessibilityPersistUtils.isOwnServiceEnabled(context)
        }

        /**
         * Attempt to re-enable the accessibility service.
         *
         * - If `WRITE_SECURE_SETTINGS` is granted: writes directly to
         *   `Settings.Secure` to re-arm the service (instant, no user
         *   interaction).
         * - If not granted: posts a high-priority notification prompting
         *   the user to re-enable it manually (Android 13+ cannot
         *   programmatically enable accessibility without the permission).
         */
        fun selfHeal(context: Context) {
            try {
                // Try the programmatic re-arm first.
                val reArmed = AccessibilityPersistUtils.selfHealAccessibilityService(context)
                if (!reArmed) {
                    // Permission missing or write failed — fall back to notification.
                    protect.yourself.commons.utils.notificationUtils.NotificationHelper
                        .showAccessibilityDisabledNotification(context)
                }
            } catch (t: Throwable) {
                Timber.e(t, "AccessibilityGuard: selfHeal failed")
            }
        }
    }
}
