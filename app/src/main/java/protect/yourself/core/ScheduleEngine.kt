package protect.yourself.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import protect.yourself.commons.utils.broadcastReceivers.ScheduleAlarmReceiver
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import timber.log.Timber
import java.util.Calendar
import kotlinx.coroutines.launch

/**
 * Singleton coordinator that bridges the database, alarm scheduling, and app
 * restriction enforcement.
 *
 * Manages two runtime states:
 *   - [_blockedPackages] — set of package names blocked by active "launch_blocking" schedules.
 *   - [_internetBlocked] — whether internet is blocked by an active "internet" schedule.
 *
 * These are read by [MyAccessibilityService] and [MyVpnService] respectively.
 */
object ScheduleEngine {

    @Volatile
    private var _blockedPackages: Set<String> = emptySet()

    @Volatile
    private var _internetBlocked: Boolean = false

    /** Returns the current set of blocked package names. */
    fun getBlockedPackages(): Set<String> = _blockedPackages

    /** Returns whether internet is currently blocked. */
    fun isInternetBlocked(): Boolean = _internetBlocked

    /**
     * Called once from Application.onCreate.  Verifies the Room database is
     * ready before any schedule re-evaluation is attempted.
     */
    suspend fun initialize(context: Context) {
        Timber.i("ScheduleEngine: initializing")
        try {
            AppDatabase.getInstance(context).scheduledRestrictionDao().getAll()
            Timber.i("ScheduleEngine: DB ready")
        } catch (t: Throwable) {
            Timber.w(t, "ScheduleEngine: DB not ready yet — will retry on next cycle")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Core re-evaluation
    // ─────────────────────────────────────────────────────────────────

    /**
     * Core method: queries all enabled schedules from the DB, filters those
     * currently active via [ScheduleEvaluator], computes the union of
     * blocked packages and the internet-blocked flag, then stores the result
     * and schedules the next alarm.
     */
    suspend fun reevaluateAndApply(context: Context) {
        try {
            Timber.i("ScheduleEngine: re-evaluating scheduled restrictions")
            val db = AppDatabase.getInstance(context)
            val enabledSchedules = db.scheduledRestrictionDao().getAllEnabled()

            val activeSchedules = ScheduleEvaluator.getActiveSchedules(enabledSchedules)

            var blockInternet = false
            val blockedPackages = mutableSetOf<String>()

            for (schedule in activeSchedules) {
                when (schedule.type) {
                    "launch_blocking" -> {
                        try {
                            val apps = db.scheduledRestrictionAppDao()
                                .getAppsForRestriction(schedule.restrictionKey)
                            for (app in apps) {
                                app.packageName?.let { blockedPackages.add(it) }
                            }
                        } catch (t: Throwable) {
                            Timber.w(t, "ScheduleEngine: failed loading apps for %s",
                                schedule.restrictionKey)
                        }
                    }
                    "internet" -> {
                        if (schedule.isStrictMode) {
                            blockInternet = true
                        }
                    }
                    else -> {
                        Timber.d("ScheduleEngine: unknown type '%s' for '%s'",
                            schedule.type, schedule.restrictionKey)
                    }
                }
            }

            applyRestrictions(context, blockInternet, blockedPackages)
            scheduleNextAlarm(context)
        } catch (t: Throwable) {
            Timber.e(t, "ScheduleEngine: reevaluateAndApply failed")
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  State storage
    // ─────────────────────────────────────────────────────────────────

    private fun applyRestrictions(
        context: Context,
        blockInternet: Boolean,
        blockedPackages: Set<String>
    ) {
        val oldInternetBlocked = _internetBlocked
        _blockedPackages = blockedPackages
        _internetBlocked = blockInternet
        Timber.i("ScheduleEngine: restrictions applied — blockInternet=%s, blockedPackages=%d",
            blockInternet, blockedPackages.size)
        if (oldInternetBlocked != blockInternet) {
            Timber.i("ScheduleEngine: internet-blocked changed %b->%b — restarting VPN",
                oldInternetBlocked, blockInternet)
            try {
                protect.yourself.features.blockerPage.service.MyVpnService.restart(context)
            } catch (t: Throwable) {
                Timber.w(t, "Failed to restart VPN from ScheduleEngine")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Alarm scheduling
    // ─────────────────────────────────────────────────────────────────

    /**
     * Schedules the next exact alarm at the nearest schedule boundary
     * (start or end time of any schedule).  Uses AlarmManager with
     * [ScheduleAlarmReceiver] to trigger re-evaluation at that moment.
     */
    suspend fun scheduleNextAlarm(context: Context) {
        try {
            val db = AppDatabase.getInstance(context)
            val schedules = db.scheduledRestrictionDao().getAll()

            val now = System.currentTimeMillis()
            val nextBoundary = findNextBoundary(schedules, now) ?: run {
                Timber.d("ScheduleEngine: no future schedule boundaries found")
                return
            }

            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, ScheduleAlarmReceiver::class.java).apply {
                action = ScheduleAlarmReceiver.ACTION_SCHEDULE_CHECK
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_SCHEDULE_CHECK,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                nextBoundary,
                pendingIntent
            )
            Timber.i("ScheduleEngine: next alarm at %d", nextBoundary)
        } catch (t: Throwable) {
            Timber.w(t, "ScheduleEngine: scheduleNextAlarm failed")
        }
    }

    /** Reschedules all alarms (convenience — currently delegates to scheduleNextAlarm). */
    suspend fun scheduleAllAlarms(context: Context) {
        scheduleNextAlarm(context)
    }

    /**
     * Invoked when the device boots.  Launches a coroutine to re-evaluate
     * all schedules so restrictions are applied immediately after start-up.
     */
    fun onBootCompleted(context: Context) {
        appCoroutineScope(
            scopeName = "ScheduleEngine-onBoot",
            dispatcher = kotlinx.coroutines.Dispatchers.IO
        ).launch {
            try {
                reevaluateAndApply(context)
                Timber.i("ScheduleEngine: boot re-evaluation complete")
            } catch (t: Throwable) {
                Timber.w(t, "ScheduleEngine: boot re-evaluation failed")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────
    //  Private helpers
    // ─────────────────────────────────────────────────────────────────

    /**
     * Parses a daysOfWeek string into [Calendar.DAY_OF_WEEK] constants
     * (1=Sunday .. 7=Saturday).  Returns an empty set for "*" (all days),
     * or null for invalid / empty input.
     */
    private fun parseDays(daysOfWeek: String): Set<Int>? {
        if (daysOfWeek == "*") return emptySet()
        if (daysOfWeek.isEmpty()) return null
        return try {
            daysOfWeek.split(",")
                .map { it.trim().toInt() }
                .map { it + 1 }   // 0→1 (Sun) … 6→7 (Sat)
                .toSet()
        } catch (e: NumberFormatException) {
            null
        }
    }

    /**
     * Iterates through the next 14 days and finds the earliest future
     * timestamp where any schedule starts or ends.
     */
    private fun findNextBoundary(
        schedules: List<ScheduledRestrictionItemModel>,
        nowMillis: Long
    ): Long? {
        var nearest: Long? = null

        for (schedule in schedules) {
            val days = parseDays(schedule.daysOfWeek) ?: continue

            for (dayOffset in 0..13) {
                val day = Calendar.getInstance().apply { timeInMillis = nowMillis }
                day.add(Calendar.DAY_OF_YEAR, dayOffset)
                val dow = day.get(Calendar.DAY_OF_WEEK)

                // Skip if this schedule is not active on this day-of-week
                if (days.isNotEmpty() && dow !in days) continue

                // --- Start boundary ---
                val start = Calendar.getInstance().apply {
                    timeInMillis = day.timeInMillis
                    set(Calendar.HOUR_OF_DAY, schedule.startTimeMinutes / 60)
                    set(Calendar.MINUTE, schedule.startTimeMinutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val startMs = start.timeInMillis
                if (startMs > nowMillis && (nearest == null || startMs < nearest)) {
                    nearest = startMs
                }

                // --- End boundary ---
                val end = Calendar.getInstance().apply {
                    timeInMillis = day.timeInMillis
                    set(Calendar.HOUR_OF_DAY, schedule.endTimeMinutes / 60)
                    set(Calendar.MINUTE, schedule.endTimeMinutes % 60)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                // If end crosses midnight, add one day
                if (schedule.endTimeMinutes < schedule.startTimeMinutes) {
                    end.add(Calendar.DAY_OF_YEAR, 1)
                }
                val endMs = end.timeInMillis
                if (endMs > nowMillis && (nearest == null || endMs < nearest)) {
                    nearest = endMs
                }
            }
        }
        return nearest
    }

    private const val REQUEST_CODE_SCHEDULE_CHECK = 8100
}
