package protect.yourself.database.pendingRequests

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Pending requests for accountability partner approval (Real Friend mode).
 */
@Entity(tableName = "partner_pending_request_table")
data class PendingRequestItemModel(
    @PrimaryKey val key: String,
    val requestIdentifier: String,
    val appName: String,
    val keyWord: String,
    val packageName: String,
    val switchNumber: Int,
    val itemKey: String,
    val itemType: String,
    val requestDisplayMessage: String,
    val requestSubmitTime: Long,
    val requestOffTime: Long,
    val apType: Int,
    val approvalType: Int
)
