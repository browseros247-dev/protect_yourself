package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Receiver for package install/remove/replace events.
 * Used by "block new install apps" feature.
 *
 * Phase 3 — full implementation.
 */
class AppSystemActionReceiverAllTimeWithData : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("Package event: ${intent.action} data=${intent.data}")
        // TODO Phase 3
    }
}
