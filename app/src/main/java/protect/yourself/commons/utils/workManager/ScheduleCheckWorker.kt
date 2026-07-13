package protect.yourself.commons.utils.workManager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import protect.yourself.core.ScheduleEngine
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * Periodic safety-net worker that re-evaluates scheduled restrictions.
 *
 * Provides defense-in-depth: if AlarmManager alarms are lost (e.g. device
 * deep sleep, OEM battery optimisation), this WorkManager worker ensures
 * schedules are still checked within 30 minutes.
 *
 * Pattern follows [AppDataCheckWorker] exactly:
 *   - [CoroutineWorker] with [Result.success] / [Result.retry]
 *   - Companion object constants including [WORK_NAME] and [enqueue] helper
 */
class ScheduleCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.i("ScheduleCheckWorker: running")
            ScheduleEngine.reevaluateAndApply(applicationContext)
            Timber.i("ScheduleCheckWorker: completed successfully")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "ScheduleCheckWorker: failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "schedule_check_worker"

        /**
         * Enqueues the periodic schedule check worker.
         * Uses [ExistingPeriodicWorkPolicy.KEEP] so the worker is only
         * scheduled once across app restarts / updates.
         *
         * @param context application context
         */
        fun enqueue(context: Context) {
            try {
                val request = PeriodicWorkRequestBuilder<ScheduleCheckWorker>(
                    30, TimeUnit.MINUTES
                )
                    .setInitialDelay(1, TimeUnit.MINUTES)
                    .build()

                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request
                )
                Timber.i("ScheduleCheckWorker: enqueued (every 30 min)")
            } catch (t: Throwable) {
                Timber.e(t, "ScheduleCheckWorker: enqueue failed")
            }
        }
    }
}
