package protect.yourself.commons.utils.workManager

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import timber.log.Timber

/**
 * Periodic app data check worker.
 *
 * Original behavior (ported from WorkerUtils.initAppDataCheckWorker):
 *  - Runs every 24 hours
 *  - Checks DB integrity
 *  - Re-applies accessibility blocking values if drifted
 *  - Updates streak data (rolls over to next day if needed)
 *  - Checks for due Stop Me scheduled sessions
 *
 * Phase 2: minimal implementation — just logs + ensures DB is healthy.
 */
class AppDataCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return try {
            Timber.i("AppDataCheckWorker running")

            val db = AppDatabase.getInstance(applicationContext)

            // 1. Verify block screen count exists
            val blockCount = db.blockScreenCountDao().getCount()
            if (blockCount == null) {
                db.blockScreenCountDao().upsert(
                    protect.yourself.database.blockScreensCount.BlockScreenCountItemModel(0, 0)
                )
                Timber.w("Block screen count was missing — re-initialized")
            }

            // 2. Verify Stop Me session count exists
            val stopMeCount = db.stopMeSessionCountDao().get()
            if (stopMeCount == null) {
                db.stopMeSessionCountDao().upsert(
                    protect.yourself.database.stopMeSessionCount.StopMeSessionCountItemModel(0, 0)
                )
                Timber.w("Stop Me session count was missing — re-initialized")
            }

            // 3. Verify default switches exist (spot check)
            val pornBlocker = db.switchStatusDao().get(
                protect.yourself.database.switchStatus.SwitchIdentifier.PORN_BLOCKER_SWITCH
            )
            if (pornBlocker == null) {
                Timber.w("Porn blocker switch missing — DB may need re-population")
            }

            // TODO Phase 3+: re-apply accessibility blocking values
            // TODO Phase 5: streak date rollover
            // TODO Phase 5: due Stop Me scheduled sessions

            Timber.i("AppDataCheckWorker completed successfully")
            Result.success()
        } catch (t: Throwable) {
            Timber.e(t, "AppDataCheckWorker failed")
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "app_data_check_worker"
        const val CHANNEL_ID = "app_data_check_channel"

        /**
         * Create the notification channel (Android 8+).
         * Called from ProtectYourselfApp.onCreate.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val name = "App Data Check"
                val description = "Periodic background data verification"
                val channel = NotificationChannel(CHANNEL_ID, name, NotificationManager.IMPORTANCE_LOW).apply {
                    this.description = description
                    setShowBadge(false)
                }
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(channel)
            }
        }
    }
}
