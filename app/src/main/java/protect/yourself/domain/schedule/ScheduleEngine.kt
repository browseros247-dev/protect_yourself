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

            if (protect.yourself.BuildConfig.DEBUG) {
                Timber.w("DEBUG ScheduleEngine: ${rules.size} enabled rules")
                for (rule in rules) {
                    val apps = appsByRule[rule.key] ?: emptyList()
                    Timber.w("DEBUG ScheduleEngine: rule '${rule.name}' type=${rule.type} start=${rule.startTimeMinutes} end=${rule.endTimeMinutes} days=${rule.daysOfWeek} apps=$apps")
                }
            }

            val active = ScheduleEvaluator.evaluate(rules, appsByRule)

            Timber.i("ScheduleEngine: ${rules.size} enabled rules → " +
                "${active.internetBlockedPackages.size} internet-blocked, " +
                "${active.launchBlockedPackages.size} launch-blocked apps")

            if (protect.yourself.BuildConfig.DEBUG) {
                Timber.w("DEBUG ScheduleEngine: internetBlocked=${active.internetBlockedPackages}")
                Timber.w("DEBUG ScheduleEngine: launchBlocked=${active.launchBlockedPackages}")
                Timber.w("DEBUG ScheduleEngine: lastInternetBlocked=$lastInternetBlockedSet")
                Timber.w("DEBUG ScheduleEngine: lastLaunchBlocked=$lastLaunchBlockedSet")
            }

            // Update Accessibility cache (launch blocking)
            // AUDIT FIX: if the Accessibility Service is not running (instance == null),
            // the update is silently dropped. Log this so the user can see WHY
            // launch blocking isn't working. The ScheduleCheckWorker will retry
            // every 15 min — once the user enables Accessibility, the next
            // worker run will pick up the cached set.
            if (active.launchBlockedPackages != lastLaunchBlockedSet) {
                lastLaunchBlockedSet = active.launchBlockedPackages
                val serviceInstance = MyAccessibilityService.instance
                if (serviceInstance != null) {
                    serviceInstance.updateScheduledBlockApps(active.launchBlockedPackages)
                    Timber.i("ScheduleEngine: updated Accessibility cache (${active.launchBlockedPackages.size} apps)")
                } else {
                    Timber.w("ScheduleEngine: MyAccessibilityService.instance is NULL — " +
                        "launch blocking will NOT work until the user enables the Accessibility Service. " +
                        "${active.launchBlockedPackages.size} apps should be launch-blocked. " +
                        "The set is cached and will be applied when the service starts.")
                }
            }

            // Update VPN (internet blocking)
            // AUDIT FIX: same pattern — if VPN permission is not granted,
            // setScheduledBlockApps will log + show a notification.
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
