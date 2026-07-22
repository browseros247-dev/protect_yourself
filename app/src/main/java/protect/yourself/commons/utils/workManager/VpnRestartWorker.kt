package protect.yourself.commons.utils.workManager

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import protect.yourself.commons.utils.vpn.VpnRestoreHelper
import protect.yourself.core.ProtectYourselfApp
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber

/**
 * VpnRestartWorker — starts the VPN service after boot (primary start path).
 *
 * VPN-05 fix: On Android 12+ (API 31+), an app in the background cannot call
 * `Context.startForegroundService()` from a broadcast receiver — the system
 * throws `ForegroundServiceStartNotAllowedException` within 5 seconds of the
 * service calling `startForeground()`. Starting the VPN from this worker is
 * exempt: while an expedited job runs, the system places the app on the
 * temporary allowlist, which lifts the background-FGS-start restriction.
 *
 * BOOT-VPN-01 ROOT-CAUSE FIX (v1.0.63): the original implementation built
 * the request with BOTH `.setExpedited(...)` AND
 * `.setInitialDelay(2, TimeUnit.SECONDS)`. These are mutually exclusive —
 * `WorkRequest.Builder.build()` contains:
 *
 *     if (workSpec.expedited) {
 *         require(workSpec.initialDelay <= 0) { "Expedited jobs cannot be delayed" }
 *     }
 *
 * so `build()` threw `IllegalArgumentException` EVERY time, on EVERY device,
 * and the work request was never enqueued. The catch-all "fallback" then
 * called `MyVpnService.start()` directly from the BOOT_COMPLETED receiver,
 * which throws `ForegroundServiceStartNotAllowedException` on Android 12+
 * and was silently swallowed by the catch-all in the service starter.
 *
 * Net effect: the VPN NEVER auto-restarted after reboot on Android 12+,
 * and the failure was invisible (logged and swallowed twice).
 *
 * The fix:
 *  1. The expedited request carries NO initial delay. The 2-second system
 *     "settle" delay now lives inside [doWork] as a coroutine delay, which
 *     is the only legal place for it with expedited work.
 *  2. [ExistingWorkPolicy.REPLACE] (was KEEP) — a stale/stuck request with
 *     the same unique name must never block a fresh restore attempt.
 *  3. The enqueue catch path now records a structured crash-log breadcrumb
 *     so a scheduling failure is no longer invisible in field diagnostics.
 *  4. [doWork] delegates to
 *     [VpnRestoreHelper.restoreIfEnabled], which re-reads the persisted
 *     VPN_SWITCH, checks VPN consent, starts the service, and VERIFIES the
 *     service actually reached CONNECTING/CONNECTED. An unconfirmed start
 *     is retried via WorkManager backoff instead of being reported as
 *     success.
 *  5. A second, WorkManager-independent AlarmManager path is armed by
 *     [protect.yourself.commons.utils.vpn.VpnRestoreHelper.scheduleBootRestore]
 *     so a failure of this whole pipeline still does not strand the VPN.
 *
 * If the expedited quota is exhausted, the job degrades to a regular
 * (non-expedited) one via [OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST];
 * in that case the Android 12+ FGS-start exemption no longer applies and the
 * start verification will report START_NOT_CONFIRMED, which retries — and the
 * backup alarm path picks the VPN up shortly after.
 */
class VpnRestartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            // The 2 s settle delay that used to be (illegally) attached to the
            // expedited work request itself. Keeping it here lets the system
            // finish early-boot work before we start the VPN, without
            // violating the "expedited jobs cannot be delayed" constraint.
            kotlinx.coroutines.delay(SETTLE_DELAY_MS)

            val outcome = VpnRestoreHelper.restoreIfEnabled(
                applicationContext,
                trigger = "work_manager"
            )
            Timber.i("VpnRestartWorker outcome=$outcome (attempt ${runAttemptCount + 1})")

            when (outcome) {
                VpnRestoreHelper.RestoreOutcome.START_CONFIRMED,
                VpnRestoreHelper.RestoreOutcome.ALREADY_RUNNING,
                VpnRestoreHelper.RestoreOutcome.NOT_ENABLED,
                VpnRestoreHelper.RestoreOutcome.PERMISSION_REVOKED -> Result.success()

                VpnRestoreHelper.RestoreOutcome.LOCKED,
                VpnRestoreHelper.RestoreOutcome.DB_UNAVAILABLE,
                VpnRestoreHelper.RestoreOutcome.START_NOT_CONFIRMED ->
                    if (runAttemptCount < MAX_ATTEMPTS - 1) {
                        Timber.w("VpnRestartWorker: transient outcome=$outcome — retrying via WorkManager backoff")
                        Result.retry()
                    } else if (outcome == VpnRestoreHelper.RestoreOutcome.START_NOT_CONFIRMED) {
                        Timber.e("VpnRestartWorker: VPN start could not be confirmed after $MAX_ATTEMPTS attempts")
                        Result.failure()
                    } else {
                        // LOCKED / DB_UNAVAILABLE: BOOT_COMPLETED after unlock
                        // re-arms the pipeline, so don't burn a permanent failure.
                        Result.success()
                    }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // REPLACE policy cancels the stale worker when a fresh request is
            // enqueued — cancellation must propagate so the worker actually
            // stops instead of being converted into a retry that re-queues
            // the very work we just replaced.
            throw ce
        } catch (t: Throwable) {
            Timber.e(t, "VpnRestartWorker failed")
            if (runAttemptCount < MAX_ATTEMPTS - 1) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val WORK_NAME = "vpn_restart_after_boot"

        /** Delay inside [doWork] before starting the VPN (system settle window). */
        private const val SETTLE_DELAY_MS = 2_000L

        /** Total WorkManager attempts (initial run + backoff retries). */
        private const val MAX_ATTEMPTS = 3

        /**
         * Enqueues an expedited one-time work request to restore the VPN.
         *
         * DO NOT add `.setInitialDelay(...)` here: expedited work forbids an
         * initial delay and `build()` will throw
         * `IllegalArgumentException("Expedited jobs cannot be delayed")`
         * before the request is ever enqueued (BOOT-VPN-01 root cause).
         *
         * Uses [ExistingWorkPolicy.REPLACE] so a previously failed or stuck
         * request with the same name never blocks this attempt.
         */
        fun enqueue(context: Context) {
            try {
                val request = OneTimeWorkRequestBuilder<VpnRestartWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
                WorkManager.getInstance(context).enqueueUniqueWork(
                    WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
                Timber.i("VpnRestartWorker enqueued (expedited, REPLACE)")
            } catch (t: Throwable) {
                // Scheduling itself failed (e.g. WorkManager init blew up in a
                // direct-boot context). Make the failure VISIBLE in the crash
                // log — previously this was Timber-only and the silent fallback
                // below hid the BOOT-VPN-01 failure for months.
                Timber.e(t, "Failed to enqueue VpnRestartWorker")
                try {
                    ProtectYourselfApp.getCrashLogger()?.logThrowable(
                        throwable = t,
                        severity = protect.yourself.features.crashLog.CrashSeverity.ERROR,
                        tag = "VpnRestartWorker",
                        message = "Failed to enqueue VPN restore work — using direct-start fallback"
                    )
                } catch (_: Throwable) {}
                // Last-resort fallback: fire the service directly. Works on
                // API ≤ 30 and whenever the caller happens to hold an
                // FGS-start exemption; silently no-ops otherwise (the service
                // starter catches the exception). The backup AlarmManager path
                // armed by VpnRestoreHelper.scheduleBootRestore covers the
                // remaining cases on Android 12+.
                try {
                    MyVpnService.start(context)
                } catch (_: Throwable) {}
            }
        }
    }
}
