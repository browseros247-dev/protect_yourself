package protect.yourself.database.pendingRequests

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PendingRequestDao {

    @Query("SELECT * FROM partner_pending_request_table")
    suspend fun getAll(): List<PendingRequestItemModel>

    @Query("SELECT * FROM partner_pending_request_table WHERE `key` = :key")
    suspend fun getByKey(key: String): PendingRequestItemModel?

    @Query("SELECT EXISTS(SELECT 1 FROM partner_pending_request_table WHERE approvalType = 0)")
    suspend fun hasPendingRequest(): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: PendingRequestItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<PendingRequestItemModel>)

    @Query("DELETE FROM partner_pending_request_table")
    suspend fun deleteAll()

    @Query("UPDATE partner_pending_request_table SET approvalType = :approvalType WHERE `key` = :key")
    suspend fun updateApprovalStatus(key: String, approvalType: Int)
}
