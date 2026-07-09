package protect.yourself.database.blockScreensCount

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tracks total number of times a block screen has been shown.
 * Original key=0 (single-row table).
 */
@Entity(tableName = "block_screen_count_table")
data class BlockScreenCountItemModel(
    @PrimaryKey val key: Int = 0,
    val count: Int = 0
)
