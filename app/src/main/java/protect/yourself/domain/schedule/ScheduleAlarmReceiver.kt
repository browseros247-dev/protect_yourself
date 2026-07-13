package protect.yourself.domain.schedule

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.launch
import protect.yourself.core.appCoroutineScope
import timber.log.Timber

/**
 * ScheduleAlarmReceiver — fired by AlarmManager at schedule boundaries
 * (when a rule starts or stops being active).
 *
 * Calls ScheduleEngine.reevaluateAndApply() to update the VPN mode +
 * Accessibility cache, then schedules the next boundary alarm.
 *
 * This receiver is also called by the boot receiver (after reboot) and
 * by ScheduleCheckWorker (periodic safety net).
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    private val scope = appCoroutineScope(
        scopeName = "ScheduleAlarmReceiver",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("ScheduleAlarmReceiver fired")
        val pendingResult = goAsync()

        scope.launch {
            try {
                ScheduleEngine.getInstance(context).reevaluateAndApply()
            } catch (t: Throwable) {
                Timber.e(t, "ScheduleAlarmReceiver: reevaluateAndApply failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE_BOUNDARY = "protect_yourself.action.SCHEDULE_BOUNDARY"
        const val REQUEST_CODE = 4001

        /**
         * Schedule the next AlarmManager trigger at the given timestamp.
         * Uses setExactAndAllowWhileIdle on Android 12+ (with canScheduleExactAlarms
         * check), falls back to setAndAllowWhileIdle if exact alarms are not allowed.
         */
        fun scheduleAlarm(context: Context, triggerAtMillis: Long) {
            if (triggerAtMillis == Long.MAX_VALUE) {
                Timber.d("ScheduleAlarmReceiver: no future boundary — not scheduling alarm")
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_SCHEDULE_BOUNDARY
            }
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, triggerAtMillis, pending
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, triggerAtMillis, pending
                        )
                    }
                } else {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAtMillis, pending
                    )
                }
                Timber.i("ScheduleAlarmReceiver: scheduled next boundary at $triggerAtMillis")
            } catch (t: Throwable) {
                Timber.w(t, "ScheduleAlarmReceiver: failed to schedule alarm")
            }
        }

        /**
         * Cancel any pending schedule alarm.
         */
        fun cancelAlarm(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ACTION_SCHEDULE_BOUNDARY
            }
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            alarmManager.cancel(pending)
            Timber.i("ScheduleAlarmReceiver: cancelled pending alarm")
        }
    }
}
