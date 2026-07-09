package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Listens for connectivity changes (CONNECTIVITY_CHANGE).
 * Phase 6 — full implementation (re-evaluate VPN state).
 */
class AppSystemActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("System action: ${intent.action}")
        // TODO Phase 6
    }
}
