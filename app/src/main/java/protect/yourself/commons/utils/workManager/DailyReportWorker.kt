package protect.yourself.commons.utils.workManager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import protect.yourself.commons.utils.notificationUtils.NotificationHelper
import protect.yourself.database.core.AppDatabase
import protect.yourself.features.blockerPage.utils.StopMeManager
import timber.log.Timber

/**
 * DailyReportWorker — fires daily to show summary notification.
 *
 * Per user choice: daily report notification kept (other notifs removed).
 *
 * Behavior:
 *  - Reads block_screen_count from DB
 *  - Reads current streak days
 *  - Shows daily_report_channel notification with summary
 *
 * Also checks Stop Me scheduled sessions + accessibility service state.
 */
class DailyReportWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.i("DailyReportWorker running")

            val context = applicationContext
            val db = AppDatabase.getInstance(context)

            // 1. Get block count
            val blockCount = db.blockScreenCountDao().getCount()?.count ?: 0

            // 2. Get current streak (active days)
            val streakDays = db.streakDatesDao().countActiveStreakDays()

            // 3. Show daily report notification
            NotificationHelper.showDailyReportNotification(
                context = context,
                blockCount = blockCount,
                streakDays = streakDays
            )

            // 4. Check due Stop Me schedules
            StopMeManager.getInstance(context).checkDueSchedules()

            // 5. Check accessibility service state
            if (!protect.yourself.features.protectedApps.AccessibilityGuard
                .isAccessibilityServiceEnabled(context)) {
                NotificationHelper.showAccessibilityDisabledNotification(context)
            }

            Timber.i("DailyReportWorker completed: blockCount=$blockCount streakDays=$streakDays")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "DailyReportWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "daily_report_worker"
    }
}
