package protect.yourself.database.scheduledRestrictions

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ScheduledRestrictionAppDao {

    @Query("SELECT * FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey")
    suspend fun getAppsForRule(restrictionKey: String): List<ScheduledRestrictionAppItemModel>

    @Query("SELECT packageName FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey")
    suspend fun getPackagesForRule(restrictionKey: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScheduledRestrictionAppItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ScheduledRestrictionAppItemModel>)

    @Query("DELETE FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey")
    suspend fun deleteByRule(restrictionKey: String)

    @Query("DELETE FROM scheduled_restriction_apps WHERE restrictionKey = :restrictionKey AND packageName = :packageName")
    suspend fun deleteApp(restrictionKey: String, packageName: String)

    @Query("DELETE FROM scheduled_restriction_apps")
    suspend fun deleteAll()

    @Query("SELECT * FROM scheduled_restriction_apps")
    suspend fun getAll(): List<ScheduledRestrictionAppItemModel>
}
