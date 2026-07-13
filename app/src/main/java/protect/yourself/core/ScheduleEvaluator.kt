package protect.yourself.core

import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import timber.log.Timber
import java.util.Calendar

/**
 * Pure-function evaluator for scheduled restriction time windows.
 *
 * Determines whether a schedule is active at the current moment based on
 * day-of-week and time-of-day constraints.
 */
object ScheduleEvaluator {

    /**
     * Returns true if the given schedule parameters indicate an active
     * restriction at the current time.
     *
     * @param daysOfWeek comma-separated day numbers (0=Sunday .. 6=Saturday),
     *                   "*" for every day, or "" for no days.
     * @param startTimeMinutes minutes from midnight when the window opens.
     * @param endTimeMinutes minutes from midnight when the window closes.
     *                       If < startTimeMinutes, the window crosses midnight
     *                       (e.g. 22:00-02:00).
     * @param nowMillis epoch ms override for testing (defaults to now).
     */
    fun isScheduleActive(
        daysOfWeek: String?,
        startTimeMinutes: Int?,
        endTimeMinutes: Int?,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (daysOfWeek == null || startTimeMinutes == null || endTimeMinutes == null) {
            Timber.w("ScheduleEvaluator: null parameter(s) - daysOfWeek=%s, start=%s, end=%s",
                daysOfWeek, startTimeMinutes, endTimeMinutes)
            return false
        }
        if (daysOfWeek.isEmpty()) return false

        val cal = Calendar.getInstance().apply { timeInMillis = nowMillis }
        val todayDow = cal.get(Calendar.DAY_OF_WEEK) // 1=Sunday .. 7=Saturday

        val normalized = normalizeDaysOfWeek(daysOfWeek) ?: return false
        if (!isDayMatch(todayDow, normalized)) return false

        val nowMinutes = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        // Normal window: start <= end
        if (endTimeMinutes >= startTimeMinutes) {
            return nowMinutes in startTimeMinutes..endTimeMinutes
        }
        // Midnight-crossing window: e.g. 22:00-02:00
        return nowMinutes >= startTimeMinutes || nowMinutes <= endTimeMinutes
    }

    /**
     * Filters a list of schedules to only those that are currently active.
     */
    fun getActiveSchedules(
        schedules: List<ScheduledRestrictionItemModel>,
        nowMillis: Long = System.currentTimeMillis()
    ): List<ScheduledRestrictionItemModel> {
        return schedules.filter { schedule ->
            isScheduleActive(
                daysOfWeek = schedule.daysOfWeek,
                startTimeMinutes = schedule.startTimeMinutes,
                endTimeMinutes = schedule.endTimeMinutes,
                nowMillis = nowMillis
            )
        }
    }

    // ---- private helpers ----

    /**
     * Normalises a comma-separated days string into [Calendar.DAY_OF_WEEK]
     * constants.  Input uses 0=Sunday .. 6=Saturday.
     * Returns an empty set when daysOfWeek is "*" (wildcard).
     * Returns null on parse failure.
     */
    private fun normalizeDaysOfWeek(daysOfWeek: String): Set<Int>? {
        if (daysOfWeek == "*") return emptySet()
        return try {
            daysOfWeek.split(",")
                .map { it.trim().toInt() }
                .map { input -> input + 1 }   // 0→1 (Sun), 1→2 (Mon) … 6→7 (Sat)
                .toSet()
        } catch (e: NumberFormatException) {
            Timber.w(e, "ScheduleEvaluator: invalid daysOfWeek '%s'", daysOfWeek)
            null
        }
    }

    /**
     * Returns true when [todayDow] (Calendar.DAY_OF_WEEK) is in the allowed set.
     * An empty set is wildcard (match any day).
     */
    private fun isDayMatch(todayDow: Int, allowedDays: Set<Int>): Boolean {
        if (allowedDays.isEmpty()) return true   // wildcard
        return todayDow in allowedDays
    }
}
