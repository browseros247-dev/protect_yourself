package protect.yourself.domain.schedule

import android.content.Context
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.service.MyVpnService
import timber.log.Timber

/**
 * ScheduleEngine — singleton coordinator for Scheduled App Restrictions.
 *
 * The ONLY component that decides which apps are blocked. VPN and Accessibility
 * read from it, never from the DB directly.
 *
 * ## Responsibilities
 *
 * 1. Evaluate active rules (via [ScheduleEvaluator])
 * 2. Update Accessibility Service cache (launch-blocked apps)
 * 3. Restart VPN in appropriate mode (internet-blocked apps)
 * 4. Schedule next boundary alarm (via [ScheduleAlarmReceiver])
 *
 * ## Call sites
 *
 * - [ScheduleAlarmReceiver.onReceive] — at every schedule boundary
 * - [ScheduleCheckWorker.doWork] — periodic safety net (every 15 min)
 * - [AppSystemActionReceiverAllTime] — after boot (re-arm alarms)
 * - SchedulePageViewModel — after CRUD operations (add/edit/delete/toggle)
 *
 * ## Idempotent
 *
 * [reevaluateAndApply] can be called multiple times safely. It only restarts
 * the VPN when the active set actually changes.
 */
class ScheduleEngine private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var instance: ScheduleEngine? = null

        fun getInstance(context: Context): ScheduleEngine =
            instance ?: synchronized(this) {
                instance ?: ScheduleEngine(context.applicationContext).also { instance = it }
            }
    }

    private val db = AppDatabase.getInstance(context)

    @Volatile
    private var lastInternetBlockedSet: Set<String> = emptySet()

    @Volatile
    private var lastLaunchBlockedSet: Set<String> = emptySet()

    /**
     * Re-evaluate all schedule rules and apply changes to VPN + Accessibility.
     *
     * This is the main entry point. Safe to call from any thread (uses IO
     * dispatcher internally via suspend).
     */
    suspend fun reevaluateAndApply() {
        try {
            val rules = db.scheduledRestrictionDao().getAllEnabled()
            val appsByRule = mutableMapOf<String, List<String>>()
            for (rule in rules) {
                appsByRule[rule.key] = db.scheduledRestrictionAppDao()
                    .getPackagesForRule(rule.key)
            }

            val active = ScheduleEvaluator.evaluate(rules, appsByRule)

            Timber.i("ScheduleEngine: ${active.internetBlockedPackages.size} internet-blocked, " +
                "${active.launchBlockedPackages.size} launch-blocked apps")

            // Update Accessibility cache (launch blocking)
            if (active.launchBlockedPackages != lastLaunchBlockedSet) {
                lastLaunchBlockedSet = active.launchBlockedPackages
                MyAccessibilityService.instance?.updateScheduledBlockApps(active.launchBlockedPackages)
                Timber.i("ScheduleEngine: updated Accessibility cache (${active.launchBlockedPackages.size} apps)")
            }

            // Update VPN (internet blocking)
            if (active.internetBlockedPackages != lastInternetBlockedSet) {
                lastInternetBlockedSet = active.internetBlockedPackages
                if (active.internetBlockedPackages.isNotEmpty()) {
                    // Switch VPN to per-app-block mode
                    MyVpnService.setScheduledBlockApps(context, active.internetBlockedPackages)
                    Timber.i("ScheduleEngine: VPN switched to per-app-block mode for ${active.internetBlockedPackages.size} apps")
                } else {
                    // No scheduled internet blocks — clear the per-app-block set
                    // VPN will revert to normal DNS-filter mode
                    MyVpnService.clearScheduledBlockApps(context)
                    Timber.i("ScheduleEngine: VPN per-app-block cleared (no active internet blocks)")
                }
            }

            // Schedule next boundary alarm
            val nextBoundary = ScheduleEvaluator.nextBoundary(rules, appsByRule)
            if (nextBoundary != Long.MAX_VALUE) {
                ScheduleAlarmReceiver.scheduleAlarm(context, nextBoundary)
            } else {
                ScheduleAlarmReceiver.cancelAlarm(context)
            }
        } catch (t: Throwable) {
            Timber.e(t, "ScheduleEngine: reevaluateAndApply failed")
        }
    }

    /**
     * Called after boot. Re-arms all alarms.
     */
    suspend fun onBootCompleted() {
        Timber.i("ScheduleEngine: onBootCompleted — re-arming alarms")
        reevaluateAndApply()
    }

    /**
     * Get the current set of internet-blocked packages (for VPN service to query).
     */
    fun getActiveInternetBlockedApps(): Set<String> = lastInternetBlockedSet

    /**
     * Get the current set of launch-blocked packages (for Accessibility service to query).
     */
    fun getActiveLaunchBlockedApps(): Set<String> = lastLaunchBlockedSet
}
