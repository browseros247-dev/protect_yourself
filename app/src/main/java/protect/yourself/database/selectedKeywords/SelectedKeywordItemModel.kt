package protect.yourself.database.selectedKeywords

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "selected_keyword_table")
data class SelectedKeywordItemModel(
    @PrimaryKey val key: String,
    val keyword: String,
    val identifier: String,
    val isSelected: Boolean = true
)
