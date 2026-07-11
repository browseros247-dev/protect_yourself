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
 * FIX 6.1: changed from `String? = null` to `String = ""` to match the
 * DB schema (NOT NULL DEFAULT ''). The UI already handles blank names
 * by falling back to the key. For old backups without displayName, Gson
 * leaves the field as the default "" — no NPE.
 */
@Entity(tableName = "vpn_custom_dns")
data class VpnCustomDnsItemModel(
    @PrimaryKey val key: String,
    val displayName: String = "",
    val firstDns: String,
    val secondDns: String,
    val isSelected: Boolean = false
)
