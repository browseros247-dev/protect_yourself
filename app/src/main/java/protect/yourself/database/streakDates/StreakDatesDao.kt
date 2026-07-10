package protect.yourself.database.streakDates

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface StreakDatesDao {

    @Query("SELECT * FROM streak_dates_table ORDER BY startTime DESC")
    fun observeAll(): Flow<List<StreakDatesItemModel>>

    @Query("SELECT * FROM streak_dates_table WHERE type = '' ORDER BY startTime DESC")
    fun observeActiveStreakDays(): Flow<List<StreakDatesItemModel>>

    @Query("SELECT * FROM streak_dates_table WHERE type != '' ORDER BY startTime DESC")
    fun observeRelapseDays(): Flow<List<StreakDatesItemModel>>

    @Query("SELECT * FROM streak_dates_table WHERE type = :type ORDER BY startTime DESC")
    suspend fun getByType(type: String): List<StreakDatesItemModel>

    @Query("SELECT COUNT(*) FROM streak_dates_table WHERE type = ''")
    suspend fun countActiveStreakDays(): Int

    @Query("SELECT * FROM streak_dates_table")
    suspend fun getAll(): List<StreakDatesItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: StreakDatesItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<StreakDatesItemModel>)

    @Query("DELETE FROM streak_dates_table")
    suspend fun deleteAll()
}
