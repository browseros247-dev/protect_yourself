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
 * Ported + extended from the original reference `AccessibilityGuard.kt`.
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
     *
     * BUG-06 fix: replaced raw `Thread({...}).start()` with a single-threaded
     * ExecutorService. Raw Thread creation per observer fire is wasteful (each
     * thread allocates ~512KB of stack) and can lead to thread explosion if
     * the setting changes rapidly. The ExecutorService reuses a single thread
     * and queues work — much more efficient.
     */
    private val selfHealExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "A11yGuard-Rearm").apply { isDaemon = true }
    }

    private val servicesObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            onChange(selfChange, null)
        }

        override fun onChange(selfChange: Boolean, uri: Uri?) {
            val ctx = context ?: return
            // BUG-06 fix: use the single-threaded executor instead of spawning
            // a new Thread per change. The executor queues work if a previous
            // self-heal is still running, preventing concurrent self-heal races.
            try {
                selfHealExecutor.execute {
                    try {
                        AccessibilityPersistUtils.selfHealSafe(ctx)
                    } catch (t: Throwable) {
                        Timber.w(t, "AccessibilityGuard observer: selfHealSafe threw")
                    }
                }
            } catch (t: Throwable) {
                // Executor may be shut down — fall back to direct call
                Timber.w(t, "AccessibilityGuard: executor rejected task — direct call")
                try {
                    AccessibilityPersistUtils.selfHealSafe(ctx)
                } catch (_: Throwable) {}
            }
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
            // A11Y-PERSIST-03: also watch the MASTER switch URI. Settings
            // change notifications are dispatched per key — an OEM flip of
            // accessibility_enabled alone does NOT notify the
            // enabled-services URI, so without this a master-only flip would
            // only be caught by the 30s poll instead of instantly.
            cr.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                servicesObserver
            )
            Timber.i("AccessibilityGuard: ContentObserver registered (services list + master switch)")
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

    // A11Y-PERSIST-05: last observed effective state, for transition
    // breadcrumbs (diagnostics — lets crash logs show exactly WHEN the
    // service flipped, instead of only that it was found disabled later).
    @Volatile
    private var lastEffectiveState: Boolean? = null

    private fun checkAccessibilityServiceEnabled() {
        val ctx = context ?: return
        // A11Y-PERSIST-03 (v1.0.69): use the EFFECTIVE check — our entry can
        // remain in enabled_accessibility_services while an OEM/MIUI/Knox
        // component flips the master accessibility_enabled switch to 0, which
        // silently kills blocking even though the entry-only check reports
        // "enabled". The master switch must be ON *and* our entry present.
        val isEnabled = AccessibilityPersistUtils.isAccessibilityEffectivelyEnabled(ctx)
        val previous = lastEffectiveState
        lastEffectiveState = isEnabled
        if (previous != null && previous != isEnabled) {
            try {
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                    "A11yGuard",
                    "effective state transition: ${if (previous) "enabled" else "disabled"} -> ${if (isEnabled) "enabled" else "disabled"}"
                )
            } catch (_: Throwable) {}
        }
        if (!isEnabled) {
            Timber.w("AccessibilityGuard: service effectively disabled (entry and/or master switch) — attempting self-heal")
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
         *
         * BUG-24 fix: the notification is throttled to once per hour. Without
         * throttling, the 30-second polling loop + ContentObserver would spam
         * the notification every 30 seconds if the user keeps the service
         * disabled — extremely annoying and causes notification fatigue.
         */
        fun selfHeal(context: Context) {
            try {
                // Try the programmatic re-arm first.
                val reArmed = AccessibilityPersistUtils.selfHealAccessibilityService(context)
                if (!reArmed) {
                    // Permission missing or write failed — fall back to notification.
                    // BUG-24 fix: throttle to once per hour.
                    if (shouldShowDisabledNotification(context)) {
                        protect.yourself.commons.utils.notificationUtils.NotificationHelper
                            .showAccessibilityDisabledNotification(context)
                        recordNotificationShown(context)
                    } else {
                        Timber.d("AccessibilityGuard: notification throttled (already shown recently)")
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "AccessibilityGuard: selfHeal failed")
            }
        }

        /**
         * BUG-24 fix: throttle the "accessibility disabled" notification to
         * once per hour. The timestamp is persisted in SharedPreferences so
         * it survives app restarts (prevents spam across process death + relaunch).
         */
        private const val NOTIFICATION_THROTTLE_MS = 60 * 60 * 1000L  // 1 hour
        private const val PREFS_NAME = "accessibility_guard_prefs"
        private const val KEY_LAST_NOTIF_MS = "last_disabled_notif_ms"

        private fun shouldShowDisabledNotification(context: Context): Boolean {
            return try {
                val prefs = context.applicationContext
                    .getSharedPreferences(PREFS_NAME, 0)
                val lastMs = prefs.getLong(KEY_LAST_NOTIF_MS, 0L)
                val nowMs = System.currentTimeMillis()
                nowMs - lastMs >= NOTIFICATION_THROTTLE_MS
            } catch (_: Throwable) {
                true  // If prefs read fails, show the notification (safe default)
            }
        }

        private fun recordNotificationShown(context: Context) {
            try {
                context.applicationContext
                    .getSharedPreferences(PREFS_NAME, 0)
                    .edit()
                    .putLong(KEY_LAST_NOTIF_MS, System.currentTimeMillis())
                    .apply()
            } catch (_: Throwable) {}
        }
    }
}
