package protect.yourself.database.scheduledRestrictions

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A scheduled restriction that blocks internet (VPN) or app launches (Accessibility)
 * during a configured time window on specific days of the week.
 */
@Entity(tableName = "scheduled_restrictions")
data class ScheduledRestrictionItemModel(
    @PrimaryKey val restrictionKey: String,
    val name: String,
    val type: String,
    val startTimeMinutes: Int,
    val endTimeMinutes: Int,
    val daysOfWeek: String,
    val isEnabled: Boolean = true,
    val isStrictMode: Boolean = false,
    val focusProfile: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
