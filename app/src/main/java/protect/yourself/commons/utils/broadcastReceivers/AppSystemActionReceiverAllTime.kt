package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import protect.yourself.core.appCoroutineScope
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import timber.log.Timber

/**
 * Always-on receiver for boot + screen state events.
 *
 * Layer 3 of uninstall protection — ensures services restart after reboot.
 *
 * Actions handled:
 *  - BOOT_COMPLETED, REBOOT, LOCKED_BOOT_COMPLETED
 *  - QUICKBOOT_POWERON (htc + comhtc)
 *  - SCREEN_ON, USER_PRESENT
 *  - MY_PACKAGE_REPLACED (app updated)
 */
class AppSystemActionReceiverAllTime : BroadcastReceiver() {

    private val scope = appCoroutineScope(
        scopeName = "AppSystemActionReceiverAllTime",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("AllTime action: ${intent.action}")
        // Mirror NopoX: attempt self-heal on EVERY system event we receive.
        // This is the cheapest possible insurance — if WRITE_SECURE_SETTINGS
        // is granted, the call is a few-millisecond Settings.Secure write;
        // if not, it's a no-op. Either way, the service gets re-armed as
        // early as possible after boot/screen-on/user-present.
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(context)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in AllTime receiver failed")
        }
        val pendingResult = goAsync()

        scope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_REBOOT,
                    Intent.ACTION_LOCKED_BOOT_COMPLETED,
                    "android.intent.action.QUICKBOOT_POWERON",
                    "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                        Timber.i("Boot/reboot detected — restarting services")

                        // Refresh accessibility blocking config
                        MyAccessibilityService.instance?.refreshBlockingConfig()

                        // Restart VPN if it was active before reboot.
                        // VPN-05 fix: on Android 12+ (API 31+) a broadcast
                        // receiver cannot start a foreground service directly —
                        // the system throws ForegroundServiceStartNotAllowedException.
                        // Schedule an expedited WorkManager request instead,
                        // which is exempt from the background-start restriction.
                        try {
                            val db = AppDatabase.getInstance(context)
                            val switchValues = SwitchStatusValues(db.switchStatusDao())
                            if (switchValues.isVpnSwitchOn()) {
                                protect.yourself.commons.utils.workManager.VpnRestartWorker
                                    .enqueue(context)
                                Timber.i("VPN restart scheduled after boot (via WorkManager)")
                            }
                        } catch (t: Throwable) {
                            Timber.w(t, "Failed to check VPN state after boot")
                        }

                        // Show notification that protection is active
                        try {
                            protect.yourself.commons.utils.notificationUtils.NotificationHelper
                                .showDailyReportNotification(
                                    context,
                                    blockCount = try {
                                        AppDatabase.getInstance(context).blockScreenCountDao()
                                            .getCount()?.count ?: 0
                                    } catch (_: Throwable) { 0 },
                                    streakDays = 0
                                )
                        } catch (_: Throwable) {}
                    }

                    Intent.ACTION_SCREEN_ON,
                    Intent.ACTION_USER_PRESENT -> {
                        // Refresh accessibility blocking when screen turns on
                        MyAccessibilityService.instance?.refreshBlockingConfig()
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to handle system action: ${intent.action}")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
