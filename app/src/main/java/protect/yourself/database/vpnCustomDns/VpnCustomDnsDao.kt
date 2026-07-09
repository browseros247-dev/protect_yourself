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

    @Query("SELECT * FROM vpn_custom_dns WHERE firstDns = :dns1 AND secondDns = :dns2 LIMIT 1")
    suspend fun getByDns(dns1: String, dns2: String): VpnCustomDnsItemModel?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: VpnCustomDnsItemModel)

    @Query("UPDATE vpn_custom_dns SET isSelected = (`key` = :key)")
    suspend fun setSelected(key: String)

    @Query("DELETE FROM vpn_custom_dns WHERE `key` = :key")
    suspend fun deleteByKey(key: String)
}
