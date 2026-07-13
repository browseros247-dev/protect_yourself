package protect.yourself.database.scheduledRestrictions

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A scheduled app restriction rule.
 *
 * @param key UUID — unique identifier
 * @param name User-facing name (e.g. "YouTube Work Hours")
 * @param type Restriction type: "internet" | "launch" | "both"
 * @param startTimeMinutes Minutes from midnight (0-1439) for schedule start
 * @param endTimeMinutes Minutes from midnight (0-1439) for schedule end.
 *        If endTime < startTime, the schedule wraps to the next day
 *        (e.g. 22:00 → 06:00).
 * @param daysOfWeek Bitmask: bit 0 = Sunday (1), bit 1 = Monday (2), …, bit 6 = Saturday (64).
 *        127 = every day. 0 = no days (rule never active — should be rejected at UI level).
 * @param isEnabled Master toggle for this rule
 * @param isStrictMode Phase 7: cannot be disabled until period ends
 * @param focusProfile Phase 7: "study" | "work" | "sleep" | "custom" ("" = none)
 * @param createdAt Creation timestamp (epoch millis)
 * @param updatedAt Last modification timestamp (epoch millis)
 */
@Entity(tableName = "scheduled_restrictions")
data class ScheduledRestrictionItemModel(
    @PrimaryKey val key: String,
    val name: String,
    val type: String,
    val startTimeMinutes: Int,
    val endTimeMinutes: Int,
    val daysOfWeek: Int,
    val isEnabled: Boolean = true,
    val isStrictMode: Boolean = false,
    val focusProfile: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
