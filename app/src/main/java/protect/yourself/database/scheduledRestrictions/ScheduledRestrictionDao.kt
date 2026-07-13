package protect.yourself.database.scheduledRestrictions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduledRestrictionDao {

    @Query("SELECT * FROM scheduled_restrictions ORDER BY createdAt DESC")
    suspend fun getAll(): List<ScheduledRestrictionItemModel>

    @Query("SELECT * FROM scheduled_restrictions WHERE isEnabled = 1 ORDER BY createdAt DESC")
    suspend fun getAllEnabled(): List<ScheduledRestrictionItemModel>

    @Query("SELECT * FROM scheduled_restrictions WHERE `key` = :key LIMIT 1")
    suspend fun getByKey(key: String): ScheduledRestrictionItemModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScheduledRestrictionItemModel)

    @Query("DELETE FROM scheduled_restrictions WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM scheduled_restrictions")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ScheduledRestrictionItemModel>)
}
