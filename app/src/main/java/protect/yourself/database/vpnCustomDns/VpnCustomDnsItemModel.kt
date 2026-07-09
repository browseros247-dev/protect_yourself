package protect.yourself.database.vpnCustomDns

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vpn_custom_dns")
data class VpnCustomDnsItemModel(
    @PrimaryKey val key: String,
    val firstDns: String,
    val secondDns: String,
    val isSelected: Boolean = false
)
