package protect.yourself.database.scheduledRestrictions

import androidx.room.Entity
import androidx.room.ForeignKey

/**
 * An app associated with a scheduled restriction.
 * Deletion of the parent restriction cascades to remove all its app entries.
 */
@Entity(
    tableName = "scheduled_restriction_apps",
    primaryKeys = ["restrictionKey", "packageName"],
    foreignKeys = [
        ForeignKey(
            entity = ScheduledRestrictionItemModel::class,
            parentColumns = ["restrictionKey"],
            childColumns = ["restrictionKey"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class ScheduledRestrictionAppItemModel(
    val restrictionKey: String,
    val packageName: String,
    val appName: String
)
