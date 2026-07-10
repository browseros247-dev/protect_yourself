package protect.yourself.database.blockScreensCount

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BlockScreenCountDao {

    @Query("SELECT * FROM block_screen_count_table WHERE `key` = 0")
    fun observeCount(): Flow<BlockScreenCountItemModel?>

    @Query("SELECT * FROM block_screen_count_table")
    suspend fun getAll(): List<BlockScreenCountItemModel>

    @Query("SELECT * FROM block_screen_count_table WHERE `key` = 0")
    suspend fun getCount(): BlockScreenCountItemModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: BlockScreenCountItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BlockScreenCountItemModel>)

    @Query("UPDATE block_screen_count_table SET count = count + 1 WHERE `key` = 0")
    suspend fun increment()

    @Query("UPDATE block_screen_count_table SET count = 0 WHERE `key` = 0")
    suspend fun reset()

    @Query("DELETE FROM block_screen_count_table")
    suspend fun deleteAll()
}
