package protect.yourself.database.stopMeSessionCount

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StopMeSessionCountDao {

    @Query("SELECT * FROM stop_me_session_count_table WHERE `key` = 0")
    fun observe(): Flow<StopMeSessionCountItemModel?>

    @Query("SELECT * FROM stop_me_session_count_table WHERE `key` = 0")
    suspend fun get(): StopMeSessionCountItemModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StopMeSessionCountItemModel)

    @Query("UPDATE stop_me_session_count_table SET duration = duration + 1 WHERE `key` = 0")
    suspend fun increment()
}
