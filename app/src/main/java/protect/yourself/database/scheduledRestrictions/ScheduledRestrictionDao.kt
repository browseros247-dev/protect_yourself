package protect.yourself.database.scheduledRestrictions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledRestrictionDao {

    @Query("SELECT * FROM scheduled_restrictions ORDER BY startTimeMinutes ASC")
    fun observeAll(): Flow<List<ScheduledRestrictionItemModel>>

    @Query("SELECT * FROM scheduled_restrictions WHERE restrictionKey = :restrictionKey")
    suspend fun get(restrictionKey: String): ScheduledRestrictionItemModel?

    @Query("SELECT * FROM scheduled_restrictions")
    suspend fun getAll(): List<ScheduledRestrictionItemModel>

    @Query("SELECT * FROM scheduled_restrictions WHERE isEnabled = 1")
    suspend fun getAllEnabled(): List<ScheduledRestrictionItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScheduledRestrictionItemModel)

    @Query("DELETE FROM scheduled_restrictions WHERE restrictionKey = :restrictionKey")
    suspend fun deleteByKey(restrictionKey: String)
}
