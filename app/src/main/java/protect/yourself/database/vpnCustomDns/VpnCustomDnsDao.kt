package protect.yourself.database.vpnCustomDns

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VpnCustomDnsDao {

    @Query("SELECT * FROM vpn_custom_dns ORDER BY `key`")
    fun observeAll(): Flow<List<VpnCustomDnsItemModel>>

    @Query("SELECT * FROM vpn_custom_dns WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelected(): VpnCustomDnsItemModel?

    @Query("SELECT * FROM vpn_custom_dns")
    suspend fun getAll(): List<VpnCustomDnsItemModel>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VpnCustomDnsItemModel)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<VpnCustomDnsItemModel>)

    @Query("UPDATE vpn_custom_dns SET isSelected = (`key` = :key)")
    suspend fun setSelected(key: String)

    /**
     * Deletes a single preset by key.
     *
     * BUG-08 note: Room cannot enforce "default presets cannot be deleted"
     * at the DAO level (the rule is "key must NOT start with 'preset_'").
     * The ViewModel (BlockerPageViewModel.deleteCustomDnsPreset) guards
     * against this. Future callers of this DAO method MUST also guard —
     * deleting a default preset would leave the user with no DNS presets
     * for that provider, and the AppDatabaseCallback.onOpen repair would
     * re-insert it on the next DB open (causing a confusing "preset came
     * back" experience for the user).
     */
    @Query("DELETE FROM vpn_custom_dns WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM vpn_custom_dns")
    suspend fun deleteAll()
}
