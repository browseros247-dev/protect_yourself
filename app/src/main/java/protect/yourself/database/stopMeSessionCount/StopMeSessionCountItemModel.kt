package protect.yourself.database.stopMeSessionCount

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stop_me_session_count_table")
data class StopMeSessionCountItemModel(
    @PrimaryKey val key: Int = 0,
    val duration: Int = 0
)
