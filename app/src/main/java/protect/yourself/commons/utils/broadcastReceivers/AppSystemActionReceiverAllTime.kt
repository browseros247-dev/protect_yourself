package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Always-on receiver for boot + screen state events.
 *
 * Actions:
 *  - BOOT_COMPLETED, REBOOT, LOCKED_BOOT_COMPLETED
 *  - QUICKBOOT_POWERON (htc + comhtc)
 *  - SCREEN_ON, USER_PRESENT
 *
 * Phase 6 — full implementation (restart accessibility + VPN services on boot).
 */
class AppSystemActionReceiverAllTime : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("AllTime action: ${intent.action}")
        // TODO Phase 6
    }
}
