package protect.yourself.commons.utils.vpn

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import kotlinx.coroutines.delay
import protect.yourself.commons.utils.broadcastReceivers.BootVpnRestoreAlarmReceiver
import protect.yourself.commons.utils.notificationUtils.NotificationHelper
import protect.yourself.commons.utils.workManager.VpnRestartWorker
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber

/**
 * VpnRestoreHelper — single, idempotent funnel for "the VPN was enabled and
 * should be running again". Used by every auto-restore trigger:
 *
 *  - BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / QUICKBOOT receivers
 *    ([protect.yourself.commons.utils.broadcastReceivers.AppSystemActionReceiverAllTime])
 *  - MY_PACKAGE_REPLACED (app update kills the VPN tunnel with the old process)
 *  - [VpnRestartWorker] (WorkManager expedited job — primary start path)
 *  - [BootVpnRestoreAlarmReceiver] (AlarmManager one-shot — backup start path;
 *    alarm execution is exempt from the Android 12+ background-FGS-start
 *    restriction, so it works even when WorkManager could not start the VPN)
 *  - [protect.yourself.commons.utils.workManager.ScheduleCheckWorker]
 *    (15-minute periodic reconcile — last-resort safety net)
 *
 * ## Why two independent start paths?
 *
 * BOOT-VPN-01 root cause: the original boot path had ONE path — an expedited
 * WorkManager request — but the request was built with BOTH
 * `setExpedited()` AND `setInitialDelay()`, which are mutually exclusive.
 * `WorkRequest.Builder.build()` throws
 * `IllegalArgumentException("Expedited jobs cannot be delayed")` BEFORE the
 * request is ever enqueued, so the WorkManager path never ran — not once.
 * The catch-all then fell back to a direct `startForegroundService()` from
 * the BOOT_COMPLETED receiver, which throws
 * `ForegroundServiceStartNotAllowedException` on Android 12+ (API 31+) and
 * was silently swallowed. Net effect: on every Android 12+ device the VPN
 * never restarted after reboot, with zero visible error.
 *
 * To make sure no single broken link can ever strand the VPN again, boot
 * restore now arms TWO independent mechanisms:
 *
 *  1. **WorkManager expedited one-time work** (fixed to be a valid request,
 *     no initial delay). Expedited jobs run within seconds of enqueue and
 *     get a temporary FGS-start exemption from the system.
 *  2. **AlarmManager one-shot alarm ~45 s later.** Alarm delivery puts the
 *     app on the temporary allowlist, so `startForegroundService()` is
 *     allowed even on Android 12+ regardless of WorkManager quota/state.
 *
 * Both paths (plus the periodic reconcile) funnel into [restoreIfEnabled],
 * which re-reads the persisted VPN_SWITCH at execution time and no-ops if
 * the VPN is already running or the user turned it off in the meantime.
 * `MyVpnService.startVpn()` itself is guarded by an AtomicBoolean
 * check-and-set, so duplicate concurrent starts are harmless.
 *
 * ## Direct Boot / locked devices
 *
 * The Room database lives in credential-protected storage. On a fresh boot
 * with FBE, LOCKED_BOOT_COMPLETED fires while the user is still locked and
 * the DB cannot be opened. All entry points check [isUserUnlocked] first:
 * while locked we do nothing — BOOT_COMPLETED fires after the first unlock
 * and re-triggers the whole pipeline with storage available.
 */
object VpnRestoreHelper {

    /** Delay before the backup alarm fires (primary WM path runs much earlier). */
    const val BOOT_ALARM_DELAY_MS = 45_000L

    /** How long [restoreIfEnabled] waits for the service to reach CONNECTING/CONNECTED. */
    private const val START_VERIFY_TIMEOUT_MS = 8_000L
    private const val START_VERIFY_POLL_MS = 250L

    enum class RestoreOutcome {
        /** Device still locked (direct boot) — credential storage unavailable. */
        LOCKED,

        /** Room DB could not be opened/read. */
        DB_UNAVAILABLE,

        /** VPN_SWITCH is OFF — the user does not want the VPN. Nothing to do. */
        NOT_ENABLED,

        /** Service already running — idempotent no-op. */
        ALREADY_RUNNING,

        /** VPN consent revoked via system settings — DB synced OFF, user notified. */
        PERMISSION_REVOKED,

        /** Start intent sent but service did not reach CONNECTING within the verify window. */
        START_NOT_CONFIRMED,

        /** Service reached CONNECTING/CONNECTED after the start request. */
        START_CONFIRMED
    }

    /** True when the device's credential-protected storage is available. */
    fun isUserUnlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return true
        val um = context.applicationContext.getSystemService(UserManager::class.java)
        return um?.isUserUnlocked ?: true
    }

    /**
     * Arms the full boot-restore pipeline (WorkManager expedited job + backup
     * alarm) if — and only if — the persisted VPN_SWITCH is ON and the VPN is
     * not already running. Called from the boot/package-replaced receivers.
     *
     * Safe to call repeatedly: the WorkManager job uses REPLACE policy and the
     * alarm uses FLAG_UPDATE_CURRENT, so duplicates collapse into one.
     */
    suspend fun scheduleBootRestore(context: Context, trigger: String) {
        val appContext = context.applicationContext

        if (!isUserUnlocked(appContext)) {
            Timber.i("scheduleBootRestore($trigger): device still locked — deferring to BOOT_COMPLETED after unlock")
            return
        }

        val enabled = try {
            SwitchStatusValues(AppDatabase.getInstance(appContext).switchStatusDao())
                .isVpnSwitchOn()
        } catch (t: Throwable) {
            Timber.e(t, "scheduleBootRestore($trigger): failed to read VPN_SWITCH — cannot decide")
            return
        }

        if (!enabled) {
            Timber.i("scheduleBootRestore($trigger): VPN_SWITCH=OFF — nothing to restore")
            return
        }
        if (MyVpnService.isRunning()) {
            Timber.i("scheduleBootRestore($trigger): VPN already running — nothing to do")
            return
        }

        Timber.i("scheduleBootRestore($trigger): VPN was ON before shutdown — arming WorkManager job + backup alarm")
        logBreadcrumb("VpnRestore", "boot restore armed (trigger=$trigger)")

        // Path 1 — WorkManager expedited job (fixed request; runs within seconds).
        VpnRestartWorker.enqueue(appContext)

        // Path 2 — AlarmManager backup alarm (alarm execution is exempt from the
        // Android 12+ background-FGS-start restriction). Fires later and no-ops
        // if path 1 already brought the VPN up.
        scheduleBackupAlarm(appContext)
    }

    /**
     * One-shot backup alarm that re-runs [restoreIfEnabled] shortly after boot.
     * Uses an exact alarm when permitted, otherwise an inexact doze-tolerant
     * alarm — either way, the app is on the temporary allowlist while the
     * alarm's PendingIntent is dispatched, so starting the VPN foreground
     * service is allowed even on Android 12+.
     */
    fun scheduleBackupAlarm(context: Context) {
        val appContext = context.applicationContext
        val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (alarmManager == null) {
            Timber.w("scheduleBackupAlarm: AlarmManager unavailable")
            return
        }
        val pendingIntent = buildAlarmPendingIntent(appContext)
        val triggerAtMs = System.currentTimeMillis() + BOOT_ALARM_DELAY_MS
        val canUseExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            alarmManager.canScheduleExactAlarms()
        try {
            if (canUseExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            }
            Timber.i("Backup VPN restore alarm scheduled in ${BOOT_ALARM_DELAY_MS}ms (exact=$canUseExact)")
        } catch (t: Throwable) {
            // Defensive: some OEMs throw SecurityException for exact alarms
            // even when canScheduleExactAlarms() reported true.
            Timber.w(t, "scheduleBackupAlarm: exact/precise alarm failed — retrying inexact")
            try {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pendingIntent)
            } catch (t2: Throwable) {
                Timber.e(t2, "scheduleBackupAlarm: fallback inexact alarm also failed")
            }
        }
    }

    /** Cancels the backup alarm (used in tests / when the user disables the VPN). */
    fun cancelBackupAlarm(context: Context) {
        try {
            val alarmManager = context.applicationContext
                .getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.cancel(buildAlarmPendingIntent(context.applicationContext))
        } catch (t: Throwable) {
            Timber.w(t, "cancelBackupAlarm failed (non-fatal)")
        }
    }

    private fun buildAlarmPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, BootVpnRestoreAlarmReceiver::class.java)
            .setAction(BootVpnRestoreAlarmReceiver.ACTION_RESTORE)
        return PendingIntent.getBroadcast(
            context,
            BootVpnRestoreAlarmReceiver.REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * The single idempotent restore operation. Reads the persisted state at
     * execution time (never a stale caller-side snapshot) and:
     *
     *  - NO-OPs when the device is locked, the switch is OFF, or the VPN is
     *    already up;
     *  - syncs VPN_SWITCH=false + notifies the user when VPN consent was
     *    revoked in system settings;
     *  - otherwise starts [MyVpnService] and waits up to
     *    [START_VERIFY_TIMEOUT_MS] for the service to signal CONNECTING or
     *    CONNECTED, so callers can distinguish "intent sent" from "actually
     *    starting" and retry when the start silently did not take effect.
     */
    suspend fun restoreIfEnabled(context: Context, trigger: String): RestoreOutcome {
        val appContext = context.applicationContext

        if (!isUserUnlocked(appContext)) {
            Timber.i("restoreIfEnabled($trigger): device locked — skipping (BOOT_COMPLETED will re-trigger)")
            return RestoreOutcome.LOCKED
        }

        val switchValues = try {
            SwitchStatusValues(AppDatabase.getInstance(appContext).switchStatusDao())
        } catch (t: Throwable) {
            Timber.e(t, "restoreIfEnabled($trigger): DB unavailable")
            return RestoreOutcome.DB_UNAVAILABLE
        }

        val enabled = try {
            switchValues.isVpnSwitchOn()
        } catch (t: Throwable) {
            Timber.e(t, "restoreIfEnabled($trigger): failed to read VPN_SWITCH")
            return RestoreOutcome.DB_UNAVAILABLE
        }
        if (!enabled) {
            Timber.i("restoreIfEnabled($trigger): VPN_SWITCH=OFF — skipping")
            return RestoreOutcome.NOT_ENABLED
        }

        // VPN-STATE-05: treat CONNECTING/CONNECTED as running, not just the
        // post-establish isRunning flag. Overlapping triggers (boot worker,
        // backup alarm, connectivity change, periodic reconcile) would
        // otherwise fire redundant start intents while a start is already in
        // flight and then sit through the full 8 s verify window.
        val liveState = MyVpnService.observableVpnState
        if (MyVpnService.isRunning() ||
            liveState == MyVpnService.VpnState.CONNECTING ||
            liveState == MyVpnService.VpnState.CONNECTED
        ) {
            Timber.d("restoreIfEnabled($trigger): VPN already running/starting (state=$liveState) — no-op")
            return RestoreOutcome.ALREADY_RUNNING
        }

        // Permission pre-check (BUG-18): if the user revoked VPN consent in
        // system settings, starting would flash a "Connecting…" notification
        // then fail silently. Sync the DB to reality instead so the UI and
        // future restore attempts agree.
        if (android.net.VpnService.prepare(appContext) != null) {
            Timber.w("restoreIfEnabled($trigger): VPN consent revoked — syncing VPN_SWITCH=false")
            try {
                switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            } catch (t: Throwable) {
                Timber.e(t, "restoreIfEnabled($trigger): failed to sync VPN_SWITCH=false")
            }
            try {
                // VPN-NOTIF-04: pass copy that matches THIS scenario (VPN
                // protection dropped after consent was revoked) — the default
                // copy describes the scheduled-restriction case.
                NotificationHelper.showVpnPermissionRequiredNotification(
                    context = appContext,
                    title = "VPN protection is off",
                    text = "VPN permission was removed. Tap to re-enable VPN protection.",
                    bigText = "The VPN was restoring protection but its Android VPN permission was " +
                        "revoked (e.g. via system Settings → VPN). The VPN switch has been turned off " +
                        "to match. Tap to open the app and re-enable VPN protection."
                )
            } catch (t: Throwable) {
                Timber.w(t, "restoreIfEnabled($trigger): failed to post permission notification")
            }
            logBreadcrumb("VpnRestore", "permission revoked — switch synced off (trigger=$trigger)")
            return RestoreOutcome.PERMISSION_REVOKED
        }

        Timber.i("restoreIfEnabled($trigger): starting VPN…")
        logBreadcrumb("VpnRestore", "starting VPN (trigger=$trigger)")
        MyVpnService.start(appContext)

        // Verify the service actually came up. startForegroundService() can be
        // silently rejected (Android 12+ background restriction inside a
        // non-exempt fallback context), so don't trust fire-and-forget.
        val deadline = SystemClock.elapsedRealtime() + START_VERIFY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            when (MyVpnService.observableVpnState) {
                MyVpnService.VpnState.CONNECTING,
                MyVpnService.VpnState.CONNECTED -> {
                    Timber.i("restoreIfEnabled($trigger): VPN start CONFIRMED")
                    return RestoreOutcome.START_CONFIRMED
                }
                MyVpnService.VpnState.FAILED -> {
                    Timber.w("restoreIfEnabled($trigger): service reported FAILED")
                    return RestoreOutcome.START_NOT_CONFIRMED
                }
                MyVpnService.VpnState.IDLE -> delay(START_VERIFY_POLL_MS)
            }
        }
        Timber.w("restoreIfEnabled($trigger): start NOT confirmed within ${START_VERIFY_TIMEOUT_MS}ms")
        logBreadcrumb("VpnRestore", "start not confirmed (trigger=$trigger)")
        return RestoreOutcome.START_NOT_CONFIRMED
    }

    /**
     * Lightweight reconcile used by the periodic [ScheduleCheckWorker]
     * safety net: if the VPN should be running but isn't, enqueue the
     * expedited restore worker (which gets an FGS-start exemption from the
     * system — unlike a direct start from a periodic worker on Android 12+).
     */
    suspend fun ensureVpnRestoreScheduledIfNeeded(context: Context, trigger: String) {
        val appContext = context.applicationContext
        if (!isUserUnlocked(appContext)) return

        val switchValues = try {
            SwitchStatusValues(AppDatabase.getInstance(appContext).switchStatusDao())
        } catch (t: Throwable) {
            Timber.w(t, "ensureVpnRestoreScheduledIfNeeded($trigger): DB unavailable")
            return
        }

        val enabled = try {
            switchValues.isVpnSwitchOn()
        } catch (t: Throwable) {
            Timber.w(t, "ensureVpnRestoreScheduledIfNeeded($trigger): failed to read VPN_SWITCH")
            return
        }
        if (!enabled) return

        val state = MyVpnService.observableVpnState
        if (MyVpnService.isRunning() ||
            state == MyVpnService.VpnState.CONNECTING ||
            state == MyVpnService.VpnState.CONNECTED
        ) {
            return
        }

        if (android.net.VpnService.prepare(appContext) != null) {
            // Consent revoked — keep the DB in sync, don't churn restore jobs.
            try {
                switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            } catch (t: Throwable) {
                Timber.e(t, "ensureVpnRestoreScheduledIfNeeded($trigger): failed to sync VPN_SWITCH=false")
            }
            Timber.w("ensureVpnRestoreScheduledIfNeeded($trigger): VPN consent revoked — synced VPN_SWITCH=false")
            return
        }

        Timber.w("ensureVpnRestoreScheduledIfNeeded($trigger): VPN enabled but not running (state=$state) — enqueuing restore worker")
        logBreadcrumb("VpnRestore", "reconcile enqueued restore (trigger=$trigger, state=$state)")
        VpnRestartWorker.enqueue(appContext)
    }

    private fun logBreadcrumb(tag: String, message: String) {
        try {
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()
                ?.logBreadcrumb(tag, message)
        } catch (_: Throwable) {
            // Breadcrumbs are best-effort diagnostics — never break the flow.
        }
    }
}
