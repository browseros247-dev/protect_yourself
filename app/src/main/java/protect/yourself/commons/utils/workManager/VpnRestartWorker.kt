package protect.yourself.commons.utils.workManager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * VpnRestartWorker — starts the VPN service after boot.
 *
 * VPN-05 fix: On Android 12+ (API 31+), an app in the background cannot call
 * `Context.startForegroundService()` from a broadcast receiver — the system
 * throws `ForegroundServiceStartNotAllowedException` within 5 seconds of the
 * service calling `startForeground()`. The original boot-restart code in
 * `AppSystemActionReceiverAllTime` called `MyVpnService.start(context)`
 * directly from `BroadcastReceiver.onReceive()`, which would crash on
 * Android 12+ devices.
 *
 * Fix: schedule an expedited `OneTimeWorkRequest` via WorkManager. Expedited
 * work is exempt from the background-start restriction — WorkManager grants
 * the worker a temporary `foregroundServiceExemption` window that allows the
 * worker (and services it starts) to call `startForeground()`.
 *
 * If the expedited quota is exhausted, the worker falls back to a normal
 * (non-expedited) work request — it will run as soon as the system allows,
 * typically within a few minutes. This is a graceful degradation: the VPN
 * starts slightly later than ideal but still starts.
 */
class VpnRestartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = AppDatabase.getInstance(applicationContext)
            val switchValues = SwitchStatusValues(db.switchStatusDao())
            if (switchValues.isVpnSwitchOn()) {
                // BUG-18 fix: pre-check VPN permission before starting the
                // service. If the user revoked VPN permission (e.g. via
                // system Settings → VPN → Forget), VpnService.prepare()
                // returns a non-null Intent. In that case, starting the
                // service would show a brief "Connecting…" notification
                // followed by a silent failure (establish() returns null).
                // Instead, sync VPN_SWITCH=false now so the UI reflects
                // reality and no misleading notification is shown.
                if (android.net.VpnService.prepare(applicationContext) != null) {
                    Timber.w("BUG-18: VPN permission was revoked — syncing VPN_SWITCH=false instead of starting service")
                    switchValues.storeSwitchStatus(
                        protect.yourself.database.switchStatus.SwitchIdentifier.VPN_SWITCH,
                        false
                    )
                    Result.success()
                } else {
                    MyVpnService.start(applicationContext)
                    Timber.i("VPN restarted by VpnRestartWorker")
                    Result.success()
                }
            } else {
                Timber.i("VPN switch is OFF — VpnRestartWorker no-op")
                Result.success()
            }
        } catch (t: Throwable) {
            Timber.e(t, "VpnRestartWorker failed")
            // Retry once after 30 seconds, then give up.
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "vpn_restart_after_boot"

        /**
         * Schedules an expedited one-time work request to start the VPN.
         * Call from a context where the VPN may need to start in the
         * background (e.g. BOOT_COMPLETED receiver).
         *
         * Uses [ExistingWorkPolicy.KEEP] so that if a restart is already
         * scheduled, we don't queue a duplicate.
         */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<VpnRestartWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .setInitialDelay(2, TimeUnit.SECONDS)  // give the system a moment to settle
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.KEEP,
                    request
                )
                Timber.i("VpnRestartWorker scheduled")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to schedule VpnRestartWorker — falling back to direct start")
                // Last-resort fallback: try to start the VPN directly. This
                // may fail on Android 12+ but is better than nothing on
                // older Android versions.
                try {
                    MyVpnService.start(context)
                } catch (_: Throwable) {}
            }
        }
    }
}
