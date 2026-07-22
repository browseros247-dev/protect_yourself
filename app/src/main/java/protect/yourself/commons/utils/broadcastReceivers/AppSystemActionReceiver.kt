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
 * VPN-CONN-01 fix (v1.0.64): the restart previously called
 * `MyVpnService.start(context)` DIRECTLY from this broadcast receiver —
 * on Android 12+ (API 31+) that throws
 * ForegroundServiceStartNotAllowedException, which was silently swallowed
 * inside the service starter, making this path dead code on every modern
 * device (the exact same failure class as BOOT-VPN-01). The receiver now
 * schedules the expedited [VpnRestartWorker] instead — expedited jobs get
 * a temporary FGS-start exemption, and the worker re-checks switch state,
 * VPN consent, and verifies the service actually came up.
 *
 * Note: this receiver is also a defense-in-depth for BUG-13 (tunnel death
 * detection). A periodic 15-minute reconcile now also runs in
 * [protect.yourself.commons.utils.workManager.ScheduleCheckWorker]
 * (BOOT-VPN-01 fix, v1.0.63).
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
                if (!vpnShouldBeOn) return@launch
                // Treat CONNECTING/CONNECTED as running — isRunning() alone
                // misses the startup window (isRunning flips true only post-
                // establish), which would enqueue a duplicate restore while
                // a start is already in flight.
                val state = MyVpnService.observableVpnState
                val vpnRunningOrStarting = MyVpnService.isRunning() ||
                    state == MyVpnService.VpnState.CONNECTING ||
                    state == MyVpnService.VpnState.CONNECTED
                if (!vpnRunningOrStarting) {
                    // VPN-CONN-01: expedited WorkManager job (exempt from the
                    // Android 12+ background-FGS-start restriction) instead of
                    // a direct MyVpnService.start() from this receiver.
                    Timber.i("BUG-12: VPN_SWITCH is ON but service is not running on connectivity change — scheduling restore worker")
                    protect.yourself.commons.utils.workManager.VpnRestartWorker
                        .enqueue(context.applicationContext)
                }
            } catch (t: Throwable) {
                Timber.e(t, "BUG-12: failed to re-evaluate VPN state on connectivity change")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
