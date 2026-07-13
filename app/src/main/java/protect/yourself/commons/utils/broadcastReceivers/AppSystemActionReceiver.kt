package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber

/**
 * Listens for connectivity changes (CONNECTIVITY_CHANGE).
 *
 * BUG-12 fix: previously this receiver was a no-op stub ("TODO Phase 6").
 * On Android 7+ (API 24+), CONNECTIVITY_CHANGE is only delivered to
 * manifest receivers when the app is in the foreground, so this receiver
 * rarely fires in practice. However, when it DOES fire (e.g. the user is
 * in the app and toggles airplane mode or switches Wi-Fi/cellular), we
 * now re-evaluate the VPN state: if VPN_SWITCH is ON but the service is
 * not running, restart it. This catches the case where the OEM ROM killed
 * the VPN tunnel during a network transition and Android did not auto-
 * rebind it.
 *
 * Note: this receiver is also a defense-in-depth for BUG-13 (tunnel death
 * detection). For a more robust tunnel-death detection, see the periodic
 * health-check polling proposed in BUG-13 (not yet implemented).
 */
class AppSystemActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("System action: ${intent.action}")
        // BUG-12 fix: re-evaluate VPN state on connectivity change.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val switchValues = SwitchStatusValues(db.switchStatusDao())
                val vpnShouldBeOn = switchValues.isVpnSwitchOn()
                val vpnIsRunning = MyVpnService.isRunning()
                if (vpnShouldBeOn && !vpnIsRunning) {
                    Timber.i("BUG-12: VPN_SWITCH is ON but service is not running on connectivity change — restarting")
                    MyVpnService.start(context)
                }
            } catch (t: Throwable) {
                Timber.e(t, "BUG-12: failed to re-evaluate VPN state on connectivity change")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
