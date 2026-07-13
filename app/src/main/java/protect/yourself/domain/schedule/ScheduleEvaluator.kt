package protect.yourself.domain.schedule

import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import java.util.Calendar

/**
 * ScheduleEvaluator — pure function that determines which apps should be
 * blocked at a given timestamp.
 *
 * This is a PURE FUNCTION — no I/O, no side effects, no Android dependencies.
 * This makes it trivially unit-testable.
 *
 * ## Schedule types
 *
 * - "internet" → app's network traffic is blocked (via VPN per-app-block mode)
 * - "launch"  → app cannot be opened (via Accessibility Service)
 * - "both"    → both internet and launch are blocked
 *
 * ## Time representation
 *
 * - startTimeMinutes / endTimeMinutes are minutes from midnight (0-1439)
 * - If endTime < startTime, the schedule wraps to the next day
 *   (e.g. 22:00 → 06:00 = startTime=1320, endTime=360)
 *
 * ## Day-of-week bitmask
 *
 * - Bit 0 = Sunday (1), Bit 1 = Monday (2), …, Bit 6 = Saturday (64)
 * - 127 = every day
 * - 0 = no days (rule never active — should be rejected at UI level)
 *
 * ## Overlapping rules
 *
 * If multiple active rules target the same app, the union applies — if ANY
 * active rule blocks the app, it's blocked.
 */
object ScheduleEvaluator {

    /**
     * The result of evaluating all schedule rules at a given timestamp.
     *
     * @param internetBlockedPackages Package names that should have internet blocked
     * @param launchBlockedPackages Package names that should have launch blocked
     */
    data class ActiveRules(
        val internetBlockedPackages: Set<String>,
        val launchBlockedPackages: Set<String>
    )

    /**
     * Evaluate which apps should be blocked at the given timestamp.
     *
     * @param rules All schedule rules (only isEnabled=true rules are considered)
     * @param appsByRule Map of ruleKey → list of packageNames targeted by that rule
     * @param now Timestamp to evaluate at (default: current time)
     * @return ActiveRules containing the two blocked-package sets
     */
    fun evaluate(
        rules: List<ScheduledRestrictionItemModel>,
        appsByRule: Map<String, List<String>>,
        now: Long = System.currentTimeMillis()
    ): ActiveRules {
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        // Calendar.DAY_OF_WEEK: 1=Sunday, 2=Monday, ..., 7=Saturday
        // Our bitmask: bit 0 = Sunday (1), so shift by (dayOfWeek - 1)
        val dayOfWeekBit = 1 shl (calendar.get(Calendar.DAY_OF_WEEK) - 1)
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        val internetBlocked = mutableSetOf<String>()
        val launchBlocked = mutableSetOf<String>()

        for (rule in rules) {
            if (!rule.isEnabled) continue
            if (rule.daysOfWeek == 0) continue  // no days selected — never active
            if (rule.daysOfWeek and dayOfWeekBit == 0) continue  // not scheduled today

            val isActive = if (rule.startTimeMinutes <= rule.endTimeMinutes) {
                // Same-day schedule: e.g. 9:00 → 17:00
                nowMinutes in rule.startTimeMinutes until rule.endTimeMinutes
            } else {
                // Cross-midnight schedule: e.g. 22:00 → 6:00
                nowMinutes >= rule.startTimeMinutes || nowMinutes < rule.endTimeMinutes
            }

            if (isActive) {
                val apps = appsByRule[rule.key] ?: emptyList()
                when (rule.type) {
                    "internet" -> internetBlocked.addAll(apps)
                    "launch" -> launchBlocked.addAll(apps)
                    "both" -> {
                        internetBlocked.addAll(apps)
                        launchBlocked.addAll(apps)
                    }
                }
            }
        }

        return ActiveRules(internetBlocked, launchBlocked)
    }

    /**
     * Calculate the next boundary timestamp (when a rule starts or stops being
     * active) after the given timestamp. Used by ScheduleEngine to schedule the
     * next AlarmManager trigger.
     *
     * Returns Long.MAX_VALUE if no future boundary exists (e.g. no rules).
     *
     * @param rules All schedule rules
     * @param appsByRule Map of ruleKey → packageNames (used to skip rules with no apps)
     * @param now Reference timestamp
     * @return Timestamp of the next boundary, or Long.MAX_VALUE if none
     */
    fun nextBoundary(
        rules: List<ScheduledRestrictionItemModel>,
        appsByRule: Map<String, List<String>>,
        now: Long = System.currentTimeMillis()
    ): Long {
        // For simplicity, we check the next 7 days (one week) for boundary events.
        // This covers all possible day-of-week combinations.
        val calendar = Calendar.getInstance().apply { timeInMillis = now }
        val nowMinutes = calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)

        var earliestBoundary = Long.MAX_VALUE

        for (dayOffset in 0..7) {
            val dayCalendar = Calendar.getInstance().apply {
                timeInMillis = now
                add(Calendar.DAY_OF_YEAR, dayOffset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val dayOfWeekBit = 1 shl (dayCalendar.get(Calendar.DAY_OF_WEEK) - 1)

            for (rule in rules) {
                if (!rule.isEnabled) continue
                if (rule.daysOfWeek == 0) continue
                if (rule.daysOfWeek and dayOfWeekBit == 0) continue
                if (appsByRule[rule.key]?.isEmpty() != false) continue

                // Check start boundary
                val startBoundary = dayCalendar.timeInMillis + rule.startTimeMinutes * 60_000L
                if (startBoundary > now && startBoundary < earliestBoundary) {
                    earliestBoundary = startBoundary
                }

                // Check end boundary
                val endBoundary = dayCalendar.timeInMillis + rule.endTimeMinutes * 60_000L
                if (endBoundary > now && endBoundary < earliestBoundary) {
                    earliestBoundary = endBoundary
                }
            }

            if (earliestBoundary != Long.MAX_VALUE) break
        }

        return earliestBoundary
    }
}
