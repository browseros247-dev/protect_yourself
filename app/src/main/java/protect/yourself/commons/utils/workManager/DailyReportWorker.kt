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
            val switchValues = protect.yourself.database.switchStatus.SwitchStatusValues(db.switchStatusDao())

            // 4. Check due Stop Me schedules (always runs — independent of daily report toggle)
            try {
                StopMeManager.getInstance(context).checkDueSchedules()
            } catch (t: Throwable) {
                Timber.w(t, "DailyReportWorker: checkDueSchedules failed (non-fatal)")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.WARN,
                    tag = "DailyReportWorker",
                    message = "checkDueSchedules failed (non-fatal)",
                    extraContext = mapOf("worker" to "DailyReportWorker")
                )
            }

            // 5. Check accessibility service state (always runs — independent of daily report toggle)
            try {
                if (!protect.yourself.features.protectedApps.AccessibilityGuard
                    .isAccessibilityServiceEnabled(context)) {
                    NotificationHelper.showAccessibilityDisabledNotification(context)
                }
            } catch (t: Throwable) {
                Timber.w(t, "DailyReportWorker: accessibility check failed (non-fatal)")
            }

            // BUG-05 fix: check the daily report switch BEFORE showing the notification.
            // The Stop Me + accessibility checks above always run (they're independent
            // of whether the user wants the daily report notification). Only the
            // notification itself is gated by the switch.
            val dailyReportEnabled = try {
                switchValues.isDailyReportSwitchOn()
            } catch (t: Throwable) {
                Timber.w(t, "DailyReportWorker: failed to read daily report switch — defaulting to false")
                false
            }

            if (!dailyReportEnabled) {
                Timber.i("DailyReportWorker: daily report switch is OFF — skipping notification " +
                    "(Stop Me + accessibility checks still ran)")
                return Result.success()
            }

            // 1. Get block count
            val blockCount = try {
                db.blockScreenCountDao().getCount()?.count ?: 0
            } catch (t: Throwable) {
                Timber.w(t, "DailyReportWorker: failed to read block count — defaulting to 0")
                0
            }

            // 2. Get current streak (active days)
            val streakDays = try {
                db.streakDatesDao().countActiveStreakDays()
            } catch (t: Throwable) {
                Timber.w(t, "DailyReportWorker: failed to read streak days — defaulting to 0")
                0
            }

            // 3. Show daily report notification
            try {
                NotificationHelper.showDailyReportNotification(
                    context = context,
                    blockCount = blockCount,
                    streakDays = streakDays
                )
            } catch (t: Throwable) {
                Timber.e(t, "DailyReportWorker: failed to show daily report notification")
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                    tag = "DailyReportWorker",
                    message = "Failed to show daily report notification",
                    extraContext = mapOf(
                        "blockCount" to blockCount.toString(),
                        "streakDays" to streakDays.toString()
                    )
                )
            }

            Timber.i("DailyReportWorker completed: blockCount=$blockCount streakDays=$streakDays")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "DailyReportWorker failed")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                throwable = t,
                severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                tag = "DailyReportWorker",
                message = "DailyReportWorker failed with unexpected error",
                extraContext = mapOf("worker" to "DailyReportWorker")
            )
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "daily_report_worker"
    }
}
