package protect.yourself.database.selectedApps

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SelectedAppListAppsDao {

    @Query("SELECT * FROM selected_apps_table WHERE identifier = :identifier ORDER BY appName")
    fun observeByIdentifier(identifier: String): Flow<List<SelectedAppItemModel>>

    @Query("SELECT * FROM selected_apps_table WHERE identifier = :identifier AND isSelected = 1")
    suspend fun getSelectedByIdentifier(identifier: String): List<SelectedAppItemModel>

    @Query("SELECT * FROM selected_apps_table WHERE identifier = :identifier AND isSelected = 1")
    fun observeSelectedByIdentifier(identifier: String): Flow<List<SelectedAppItemModel>>

    @Query("SELECT * FROM selected_apps_table")
    suspend fun getAll(): List<SelectedAppItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SelectedAppItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SelectedAppItemModel>)

    @Query("DELETE FROM selected_apps_table WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM selected_apps_table WHERE identifier = :identifier")
    suspend fun deleteByIdentifier(identifier: String)

    @Query("DELETE FROM selected_apps_table WHERE identifier = :identifier AND packageName = :packageName")
    suspend fun deleteByIdentifierAndPackage(identifier: String, packageName: String)

    @Query("DELETE FROM selected_apps_table")
    suspend fun deleteAll()
}
