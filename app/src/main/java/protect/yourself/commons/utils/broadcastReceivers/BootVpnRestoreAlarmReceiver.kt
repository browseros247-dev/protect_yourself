package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import protect.yourself.commons.utils.vpn.VpnRestoreHelper
import protect.yourself.commons.utils.workManager.VpnRestartWorker
import protect.yourself.core.appCoroutineScope
import timber.log.Timber

/**
 * BootVpnRestoreAlarmReceiver — backup VPN restore path (BOOT-VPN-01 fix).
 *
 * Scheduled by [VpnRestoreHelper.scheduleBootRestore] via AlarmManager ~45 s
 * after boot. This is the WORKMANAGER-INDEPENDENT safety net: even if the
 * WorkManager expedited job could not start the VPN (expedited quota
 * exhausted, WM init failure, OEM job interference), execution of an
 * AlarmManager alarm places the app on the temporary allowlist, so
 * `startForegroundService()` is allowed here even on Android 12+ (API 31+).
 *
 * The receiver is manifest-declared + non-exported and only accepts its own
 * explicit action ([ACTION_RESTORE]) from an explicit in-app PendingIntent.
 *
 * All state decisions are delegated to [VpnRestoreHelper.restoreIfEnabled],
 * which re-reads the persisted VPN_SWITCH at fire time and no-ops when the
 * VPN is already running or the user disabled it in the meantime — so this
 * alarm can safely fire "blind".
 */
class BootVpnRestoreAlarmReceiver : BroadcastReceiver() {

    private val scope = appCoroutineScope(
        scopeName = "BootVpnRestoreAlarmReceiver",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESTORE) return
        Timber.i("BootVpnRestoreAlarmReceiver: backup restore alarm fired")
        val pendingResult = goAsync()
        scope.launch {
            try {
                val outcome = VpnRestoreHelper.restoreIfEnabled(
                    context.applicationContext,
                    trigger = "boot_alarm"
                )
                Timber.i("BootVpnRestoreAlarmReceiver: restore outcome=$outcome")
                if (outcome == VpnRestoreHelper.RestoreOutcome.START_NOT_CONFIRMED ||
                    outcome == VpnRestoreHelper.RestoreOutcome.DB_UNAVAILABLE
                ) {
                    // One more chance via WorkManager (REPLACE policy dedupes).
                    VpnRestartWorker.enqueue(context.applicationContext)
                }
            } catch (t: Throwable) {
                Timber.e(t, "BootVpnRestoreAlarmReceiver: restore failed")
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_RESTORE = "protect_yourself.action.VPN_RESTORE_AFTER_BOOT"
        const val REQUEST_CODE = 4102
    }
}
