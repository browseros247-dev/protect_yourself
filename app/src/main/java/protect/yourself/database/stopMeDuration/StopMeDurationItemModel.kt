package protect.yourself.database.stopMeDuration

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stop Me sessions (Focus mode).
 *
 * Instant: duration > 0, days = 0, startTime = 0, startTimeDayMillis = 0, endTime = trigger time + duration.
 * Schedule: duration > 0, days = bitmask (1=Sun..64=Sat), startTime = ms within day,
 *           startTimeDayMillis = next absolute trigger time, endTime = 0.
 */
@Entity(tableName = "stop_me_duration_table")
data class StopMeDurationItemModel(
    @PrimaryKey val key: String,
    val duration: Long,
    val endTime: Long,
    val days: Int,
    val startTime: Long,
    val startTimeDayMillis: Long
)
