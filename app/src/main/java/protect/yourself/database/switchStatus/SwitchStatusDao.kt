package protect.yourself.database.switchStatus

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SwitchStatusDao {

    @Query("SELECT * FROM switch_status")
    suspend fun getAll(): List<SwitchStatusItemModel>

    @Query("SELECT * FROM switch_status WHERE `key` = :key")
    suspend fun get(key: String): SwitchStatusItemModel?

    @Query("SELECT * FROM switch_status WHERE `key` = :key")
    fun observe(key: String): Flow<SwitchStatusItemModel?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SwitchStatusItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SwitchStatusItemModel>)

    @Query("DELETE FROM switch_status WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM switch_status")
    suspend fun deleteAll()
}
