package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import protect.yourself.features.blockerPage.utils.StopMeManager
import timber.log.Timber

/**
 * Receiver for Stop Me session start/end alarms.
 *
 * Fired by AlarmManager when a Stop Me session:
 *  - Starts (scheduled session triggered)
 *  - Ends (instant session duration elapsed)
 */
class StopMeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY) ?: return
        when (intent.action) {
            ACTION_STOP_ME_START -> {
                Timber.i("Stop Me start alarm fired: $sessionKey")
                // The actual session start happens in StopMeManager.checkDueSchedules()
                // This alarm just wakes up the device to let the worker run.
            }
            ACTION_STOP_ME_END -> {
                Timber.i("Stop Me end alarm fired: $sessionKey")
                // Stop the active session
                kotlinx.coroutines.runBlocking {
                    StopMeManager.getInstance(context).stopActiveSession()
                }
            }
        }
    }

    companion object {
        const val ACTION_STOP_ME_START = "protect.yourself.action.STOP_ME_START"
        const val ACTION_STOP_ME_END = "protect.yourself.action.STOP_ME_END"
        const val EXTRA_SESSION_KEY = "extra_session_key"
    }
}
