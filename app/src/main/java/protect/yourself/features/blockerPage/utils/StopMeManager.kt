package protect.yourself.features.blockerPage.utils

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import protect.yourself.commons.utils.broadcastReceivers.StopMeAlarmReceiver
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * StopMeManager — manages Stop Me (focus mode) sessions.
 *
 * Ported from original Stop Me logic across BlockerPageViewModel + BlockerPageUtils.
 *
 * Behavior:
 *  - startInstantSession(durationMillis): starts an instant session, schedules end alarm
 *  - startScheduledSession(days, startTime, durationMillis): schedules recurring session
 *  - stopActiveSession(): stops current session + cancels alarms
 *  - getActiveSession(): returns current active instant session, or null
 *  - checkDueSchedules(): called by AppDataCheckWorker to start due schedules
 *
 * Original used AlarmManager.setExactAndAllowWhileIdle for end-time alarms.
 */
class StopMeManager(private val context: Context) {

    private val alarmManager: AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Start an instant Stop Me session.
     * @param durationMillis session duration in milliseconds
     * @return the key of the new session, or null on failure
     */
    suspend fun startInstantSession(durationMillis: Long, label: String? = null): String? {
        return try {
            val now = System.currentTimeMillis()
            val endTime = now + durationMillis
            val key = "instant_${now}"

            val session = StopMeDurationItemModel(
                key = key,
                duration = durationMillis,
                endTime = endTime,
                days = 0,
                startTime = 0L,
                startTimeDayMillis = 0L
            )

            val db = AppDatabase.getInstance(context)
            db.stopMeDurationDao().upsert(session)

            // Schedule end alarm
            scheduleEndAlarm(key, endTime)

            // Notify accessibility service to start blocking non-whitelisted apps
            notifyAccessibilityServiceStart()

            Timber.i("Stop Me instant session started: key=$key duration=${durationMillis}ms endTime=$endTime")
            key
        } catch (t: Throwable) {
            Timber.e(t, "Failed to start Stop Me instant session")
            null
        }
    }

    /**
     * Start a scheduled Stop Me session.
     * @param days bitmask of days (1=Sun, 2=Mon, 4=Tue, ..., 64=Sat)
     * @param startTimeMillis start time of day in millis (e.g. 9 AM = 32400000)
     * @param durationMillis session duration in milliseconds
     */
    suspend fun startScheduledSession(
        days: Int,
        startTimeMillis: Long,
        durationMillis: Long
    ): String? {
        if (days == 0) {
            Timber.w("Cannot schedule session with no days selected")
            return null
        }

        return try {
            val now = System.currentTimeMillis()
            val nextTrigger = calculateNextTrigger(days, startTimeMillis, now)

            val key = "schedule_${System.currentTimeMillis()}"
            val session = StopMeDurationItemModel(
                key = key,
                duration = durationMillis,
                endTime = 0L,
                days = days,
                startTime = startTimeMillis,
                startTimeDayMillis = nextTrigger
            )

            val db = AppDatabase.getInstance(context)
            db.stopMeDurationDao().upsert(session)

            scheduleStartAlarm(key, nextTrigger)

            Timber.i("Stop Me scheduled session: key=$key days=$days startTime=$startTimeMillis nextTrigger=$nextTrigger")
            key
        } catch (t: Throwable) {
            Timber.e(t, "Failed to schedule Stop Me session")
            null
        }
    }

    /**
     * Stop the active instant session.
     */
    suspend fun stopActiveSession() {
        try {
            val now = System.currentTimeMillis()
            val db = AppDatabase.getInstance(context)
            val active = db.stopMeDurationDao().getActiveInstantSession(now)
            if (active != null) {
                db.stopMeDurationDao().deleteByKey(active.key)
                cancelEndAlarm(active.key)
                // Increment session count (session completed)
                db.stopMeSessionCountDao().increment()
                Timber.i("Stop Me session stopped + count incremented: key=${active.key}")
            }

            notifyAccessibilityServiceStop()
        } catch (t: Throwable) {
            Timber.e(t, "Failed to stop Stop Me session")
        }
    }

    /**
     * Cancel a scheduled session by key.
     */
    suspend fun cancelScheduledSession(key: String) {
        try {
            val db = AppDatabase.getInstance(context)
            db.stopMeDurationDao().deleteByKey(key)
            cancelStartAlarm(key)
            Timber.i("Stop Me scheduled session cancelled: key=$key")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to cancel scheduled session: key=$key")
        }
    }

    /**
     * Check for due scheduled sessions and start them.
     * Called by AppDataCheckWorker periodically.
     */
    suspend fun checkDueSchedules() {
        try {
            val now = System.currentTimeMillis()
            val db = AppDatabase.getInstance(context)
            val due = db.stopMeDurationDao().getDueSchedules(now)

            for (schedule in due) {
                // Start this scheduled session as an instant session
                startInstantSession(schedule.duration)

                // Reschedule for next occurrence
                val nextTrigger = calculateNextTrigger(schedule.days, schedule.startTime, now)
                db.stopMeDurationDao().upsert(schedule.copy(startTimeDayMillis = nextTrigger))
                scheduleStartAlarm(schedule.key, nextTrigger)

                Timber.i("Stop Me scheduled session triggered + rescheduled: key=${schedule.key}")
            }
        } catch (t: Throwable) {
            Timber.e(t, "Failed to check due Stop Me schedules")
        }
    }

    /**
     * Calculate the next trigger time for a scheduled session.
     * @param days bitmask (1=Sun, 2=Mon, ..., 64=Sat)
     * @param startTimeMillis time of day in millis
     * @param fromTime reference time (usually now)
     */
    fun calculateNextTrigger(days: Int, startTimeMillis: Long, fromTime: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = fromTime
            // Set start time of day
            val hours = TimeUnit.MILLISECONDS.toHours(startTimeMillis).toInt()
            val minutes = (TimeUnit.MILLISECONDS.toMinutes(startTimeMillis) % 60).toInt()
            set(Calendar.HOUR_OF_DAY, hours)
            set(Calendar.MINUTE, minutes)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If today's slot hasn't passed and today is selected, use today
        if (cal.timeInMillis <= fromTime) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Find next selected day (max 7 days to search)
        for (i in 0..7) {
            val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK) - 1  // 0=Sunday
            val dayBit = 1 shl dayOfWeek
            if (days and dayBit != 0) {
                return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // No selected day found (shouldn't happen if days != 0)
        return fromTime + TimeUnit.DAYS.toMillis(7)
    }

    private fun scheduleEndAlarm(key: String, endTime: Long) {
        val intent = Intent(context, StopMeAlarmReceiver::class.java).apply {
            action = StopMeAlarmReceiver.ACTION_STOP_ME_END
            putExtra(StopMeAlarmReceiver.EXTRA_SESSION_KEY, key)
        }
        val pending = PendingIntent.getBroadcast(
            context, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, endTime, pending
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, endTime, pending
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, endTime, pending
                )
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to schedule end alarm for $key")
        }
    }

    private fun scheduleStartAlarm(key: String, triggerAt: Long) {
        val intent = Intent(context, StopMeAlarmReceiver::class.java).apply {
            action = StopMeAlarmReceiver.ACTION_STOP_ME_START
            putExtra(StopMeAlarmReceiver.EXTRA_SESSION_KEY, key)
        }
        val pending = PendingIntent.getBroadcast(
            context, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pending
                    )
                } else {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAt, pending
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, triggerAt, pending
                )
            }
        } catch (t: Throwable) {
            Timber.w(t, "Failed to schedule start alarm for $key")
        }
    }

    private fun cancelEndAlarm(key: String) {
        val intent = Intent(context, StopMeAlarmReceiver::class.java).apply {
            action = StopMeAlarmReceiver.ACTION_STOP_ME_END
        }
        val pending = PendingIntent.getBroadcast(
            context, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    private fun cancelStartAlarm(key: String) {
        val intent = Intent(context, StopMeAlarmReceiver::class.java).apply {
            action = StopMeAlarmReceiver.ACTION_STOP_ME_START
        }
        val pending = PendingIntent.getBroadcast(
            context, key.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pending)
    }

    private fun notifyAccessibilityServiceStart() {
        protect.yourself.features.blockerPage.service.MyAccessibilityService.instance
            ?.setStopMeRunning(true)
    }

    private fun notifyAccessibilityServiceStop() {
        protect.yourself.features.blockerPage.service.MyAccessibilityService.instance
            ?.setStopMeRunning(false)
    }

    companion object {
        // Day bit constants for scheduled sessions
        const val DAY_SUNDAY = 1
        const val DAY_MONDAY = 2
        const val DAY_TUESDAY = 4
        const val DAY_WEDNESDAY = 8
        const val DAY_THURSDAY = 16
        const val DAY_FRIDAY = 32
        const val DAY_SATURDAY = 64
        const val DAY_ALL = 127

        @Volatile
        private var instance: StopMeManager? = null

        fun getInstance(context: Context): StopMeManager {
            return instance ?: synchronized(this) {
                instance ?: StopMeManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
