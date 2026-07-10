package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.features.blockerPage.utils.StopMeManager
import timber.log.Timber

/**
 * Receiver for Stop Me session start/end alarms.
 *
 * Fired by AlarmManager when a Stop Me session:
 *  - Starts (scheduled session triggered) → starts the instant session
 *  - Ends (instant session duration elapsed) → stops the session
 */
class StopMeAlarmReceiver : BroadcastReceiver() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val sessionKey = intent.getStringExtra(EXTRA_SESSION_KEY) ?: return
        val pendingResult = goAsync()

        scope.launch {
            try {
                when (intent.action) {
                    ACTION_STOP_ME_START -> {
                        Timber.i("Stop Me start alarm fired: $sessionKey")
                        // Actually start the scheduled session
                        StopMeManager.getInstance(context).checkDueSchedules()
                    }
                    ACTION_STOP_ME_END -> {
                        Timber.i("Stop Me end alarm fired: $sessionKey")
                        StopMeManager.getInstance(context).stopActiveSession()
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Stop Me alarm handling failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_STOP_ME_START = "protect_yourself.action.STOP_ME_START"
        const val ACTION_STOP_ME_END = "protect_yourself.action.STOP_ME_END"
        const val EXTRA_SESSION_KEY = "extra_session_key"
    }
}
