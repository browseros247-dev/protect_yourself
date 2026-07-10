package protect.yourself.database.stopMeDuration

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StopMeDurationDao {

    @Query("SELECT * FROM stop_me_duration_table ORDER BY duration")
    fun observeAll(): Flow<List<StopMeDurationItemModel>>

    @Query("SELECT * FROM stop_me_duration_table WHERE days = 0 ORDER BY duration")
    fun observeInstantDurations(): Flow<List<StopMeDurationItemModel>>

    @Query("SELECT * FROM stop_me_duration_table WHERE days != 0 ORDER BY startTimeDayMillis")
    fun observeScheduleDurations(): Flow<List<StopMeDurationItemModel>>

    @Query("SELECT * FROM stop_me_duration_table WHERE days != 0 AND startTimeDayMillis <= :now")
    suspend fun getDueSchedules(now: Long): List<StopMeDurationItemModel>

    @Query("SELECT * FROM stop_me_duration_table WHERE days = 0 AND endTime > :now LIMIT 1")
    suspend fun getActiveInstantSession(now: Long): StopMeDurationItemModel?

    @Query("SELECT * FROM stop_me_duration_table")
    suspend fun getAll(): List<StopMeDurationItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StopMeDurationItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<StopMeDurationItemModel>)

    @Query("DELETE FROM stop_me_duration_table WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM stop_me_duration_table")
    suspend fun deleteAll()
}
