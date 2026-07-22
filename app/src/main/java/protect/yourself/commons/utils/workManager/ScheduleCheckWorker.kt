package protect.yourself.commons.utils.workManager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import protect.yourself.domain.schedule.ScheduleEngine
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * ScheduleCheckWorker — periodic WorkManager worker that acts as a safety net
 * for the Scheduled App Restrictions feature.
 *
 * Runs every 15 minutes (the minimum allowed by WorkManager) and calls
 * [ScheduleEngine.reevaluateAndApply] to ensure schedules are up-to-date.
 *
 * This catches cases where AlarmManager exact alarms were delayed by Doze
 * mode or the app was force-stopped and restarted.
 *
 * Enqueued from [ProtectYourselfApp.onCreate].
 */
class ScheduleCheckWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.d("ScheduleCheckWorker running")
            ScheduleEngine.getInstance(applicationContext).reevaluateAndApply()
            // BOOT-VPN-01 safety net: if the VPN is enabled but somehow not
            // running (boot-restore paths failed, OEM killed the service,
            // etc.), re-enqueue the expedited restore worker. Idempotent and
            // cheap — a DB read plus a static state check per 15-min cycle.
            try {
                protect.yourself.commons.utils.vpn.VpnRestoreHelper
                    .ensureVpnRestoreScheduledIfNeeded(
                        applicationContext,
                        trigger = "schedule_check_worker"
                    )
            } catch (t: Throwable) {
                Timber.w(t, "ScheduleCheckWorker: VPN reconcile failed (non-fatal)")
            }
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "ScheduleCheckWorker failed")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "schedule_check_periodic"

        /**
         * Enqueue the periodic worker. Call from Application.onCreate.
         * Uses KEEP policy so multiple calls don't create duplicate workers.
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleCheckWorker>(
                15, TimeUnit.MINUTES  // minimum allowed by WorkManager
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("ScheduleCheckWorker enqueued (15-min periodic)")
        }
    }
}
