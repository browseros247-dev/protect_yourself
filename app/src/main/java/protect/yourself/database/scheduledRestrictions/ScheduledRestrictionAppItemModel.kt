package protect.yourself.database.scheduledRestrictions

import androidx.room.Entity

/**
 * Apps targeted by a scheduled restriction rule (many-to-many).
 *
 * @param restrictionKey FK → scheduled_restrictions.key
 * @param packageName Target app's package name
 * @param appName Cached app name for UI display (avoids PackageManager lookups)
 */
@Entity(
    tableName = "scheduled_restriction_apps",
    primaryKeys = ["restrictionKey", "packageName"]
)
data class ScheduledRestrictionAppItemModel(
    val restrictionKey: String,
    val packageName: String,
    val appName: String
)
