package protect.yourself.database.streakDates

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Streak days + relapse records.
 *
 * @param startTime day-start timestamp (UTC midnight) — primary key
 * @param endTime day-end timestamp, OR relapse timestamp if relapse occurred
 * @param type relapse type identifier (empty if no relapse on this day)
 * @param freeText user note (empty if not provided)
 */
@Entity(tableName = "streak_dates_table")
data class StreakDatesItemModel(
    @PrimaryKey val startTime: Long,
    val endTime: Long,
    val type: String = "",
    val freeText: String = ""
)
