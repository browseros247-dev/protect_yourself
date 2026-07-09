package protect.yourself.database.selectedApps

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Selected apps across multiple categories.
 *
 * @param identifier one of [protect.yourself.database.selectedApps.SelectedAppListIdentifier.value]
 */
@Entity(tableName = "selected_apps_table")
data class SelectedAppItemModel(
    @PrimaryKey val key: String,
    val packageName: String,
    val appName: String,
    val identifier: String,
    val isSelected: Boolean = true
)
