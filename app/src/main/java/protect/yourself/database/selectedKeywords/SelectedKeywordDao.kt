package protect.yourself.database.selectedKeywords

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SelectedKeywordDao {

    @Query("SELECT * FROM selected_keyword_table WHERE identifier = :identifier ORDER BY keyword")
    fun observeByIdentifier(identifier: String): Flow<List<SelectedKeywordItemModel>>

    @Query("SELECT * FROM selected_keyword_table WHERE identifier = :identifier AND isSelected = 1")
    suspend fun getSelectedByIdentifier(identifier: String): List<SelectedKeywordItemModel>

    @Query("SELECT COUNT(*) FROM selected_keyword_table WHERE identifier = :identifier AND isSelected = 1")
    suspend fun countByIdentifier(identifier: String): Int

    @Query("SELECT * FROM selected_keyword_table")
    suspend fun getAll(): List<SelectedKeywordItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: SelectedKeywordItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<SelectedKeywordItemModel>)

    @Query("DELETE FROM selected_keyword_table WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM selected_keyword_table WHERE identifier = :identifier")
    suspend fun deleteByIdentifier(identifier: String)

    @Query("DELETE FROM selected_keyword_table")
    suspend fun deleteAll()
}
