package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import protect.yourself.core.ScheduleEngine
import protect.yourself.core.appCoroutineScope
import timber.log.Timber

/**
 * AlarmManager BroadcastReceiver that triggers scheduled restriction
 * re-evaluation.
 *
 * Fired by [ScheduleEngine.scheduleNextAlarm] at the nearest schedule
 * boundary (start or end of any schedule window).
 *
 * Pattern follows [StopMeAlarmReceiver] exactly:
 *   - [appCoroutineScope] with Dispatchers.IO
 *   - [goAsync] / [pendingResult.finish] for asynchronous processing
 *   - Companion object constants for action and extras
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    private val scope = appCoroutineScope(
        scopeName = "ScheduleAlarmReceiver",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCHEDULE_CHECK) return
        val pendingResult = goAsync()

        scope.launch {
            try {
                Timber.i("ScheduleAlarmReceiver: triggered")
                ScheduleEngine.reevaluateAndApply(context)
            } catch (t: Throwable) {
                Timber.e(t, "ScheduleAlarmReceiver: handling failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_SCHEDULE_CHECK = "protect_yourself.action.SCHEDULE_CHECK"
        const val EXTRA_RESTRICTION_KEY = "extra_restriction_key"
    }
}
