package protect.yourself.database.vpnCustomDns

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A user-selectable DNS preset used by the VPN in CUSTOM mode.
 *
 * `displayName` was added in DB v9 so the UI can render provider names
 * (e.g. "Cloudflare Family", "OpenDNS FamilyShield") without looking them
 * up from [protect.yourself.features.blockerPage.utils.DefaultDnsPresets].
 *
 * `displayName` is nullable so that backups created before v1.0.35 (which
 * don't include this field) can still be deserialised by Gson without
 * throwing a NullPointerException. The UI treats null as "Unknown".
 */
@Entity(tableName = "vpn_custom_dns")
data class VpnCustomDnsItemModel(
    @PrimaryKey val key: String,
    val displayName: String? = null,
    val firstDns: String,
    val secondDns: String,
    val isSelected: Boolean = false
)
