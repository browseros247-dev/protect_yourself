package protect.yourself.commons.utils.workManager

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * WorkerUtils — schedules background work via WorkManager.
 *
 * Ported from original WorkerUtils.kt.
 */
class WorkerUtils {

    /**
     * Initialize the periodic AppDataCheckWorker.
     * Runs every 24 hours, requires battery not low.
     */
    fun initAppDataCheckWorker(context: Context) {
        try {
            AppDataCheckWorker.createNotificationChannel(context)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(true)
                .setRequiresDeviceIdle(false)
                .build()

            val request = PeriodicWorkRequestBuilder<AppDataCheckWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(15, TimeUnit.MINUTES) // delay first run
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                AppDataCheckWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("AppDataCheckWorker scheduled (every 24h)")

            // Schedule daily report worker (runs at ~9 AM each day)
            initDailyReportWorker(context)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to schedule AppDataCheckWorker")
        }
    }

    /**
     * Initialize the periodic DailyReportWorker.
     * Runs every 24 hours, shows daily summary notification.
     */

    /**
     * Initialize the periodic ScheduleCheckWorker.
     * Runs every 30 minutes as a safety-net for scheduled restriction
     * re-evaluation (defence-in-depth if AlarmManager alarms are lost).
     */
    fun initScheduleCheckWorker(context: Context) {
        try {
            protect.yourself.commons.utils.workManager.ScheduleCheckWorker.enqueue(context)
        } catch (t: Throwable) {
            Timber.e(t, "Failed to schedule ScheduleCheckWorker")
        }
    }

    private fun initDailyReportWorker(context: Context) {
        try {
            protect.yourself.commons.utils.notificationUtils.NotificationHelper
                .createAllChannels(context)

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                .setRequiresBatteryNotLow(false)
                .build()

            val request = PeriodicWorkRequestBuilder<DailyReportWorker>(
                24, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setInitialDelay(1, TimeUnit.HOURS) // delay first run by 1 hour
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                DailyReportWorker.WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Timber.i("DailyReportWorker scheduled (every 24h)")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to schedule DailyReportWorker")
        }
    }

    companion object {
        @Volatile
        private var instance: WorkerUtils? = null

        fun getInstance(): WorkerUtils {
            return instance ?: synchronized(this) {
                instance ?: WorkerUtils().also { instance = it }
            }
        }
    }
}
