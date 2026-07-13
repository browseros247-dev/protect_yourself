package protect.yourself.database.scheduledRestrictions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledRestrictionAppDao {

    @Query("SELECT * FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey ORDER BY appName ASC")
    fun observeByRestriction(restrictionKey: String): Flow<List<ScheduledRestrictionAppItemModel>>

    @Query("SELECT * FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey ORDER BY appName ASC")
    suspend fun getAppsForRestriction(restrictionKey: String): List<ScheduledRestrictionAppItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScheduledRestrictionAppItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ScheduledRestrictionAppItemModel>)

    @Query("DELETE FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey")
    suspend fun deleteForRestriction(restrictionKey: String)
}
