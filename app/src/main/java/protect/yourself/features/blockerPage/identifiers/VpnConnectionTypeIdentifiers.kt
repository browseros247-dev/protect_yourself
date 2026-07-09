package protect.yourself.features.blockerPage.identifiers

/**
 * VPN connection modes.
 *
 * Ported from original VpnConnectionTypeIdentifiers.kt.
 *
 *  - OFF: VPN disabled
 *  - NORMAL: use default DNS preset (Cloudflare Family)
 *  - POWERFUL: use stricter DNS preset (AdGuard Family)
 *  - CUSTOM: use user-selected DNS preset from vpn_custom_dns table
 */
enum class VpnConnectionTypeIdentifiers(val value: Long) {
    OFF(0),
    NORMAL(1),
    POWERFUL(2),
    CUSTOM(3);

    companion object {
        fun fromValue(value: Long): VpnConnectionTypeIdentifiers? =
            values().firstOrNull { it.value == value }

        fun fromString(s: String?): VpnConnectionTypeIdentifiers {
            if (s.isNullOrBlank()) return OFF
            return fromValue(s.toLongOrNull() ?: 0L) ?: OFF
        }
    }
}
