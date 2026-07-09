package protect.yourself.features.blockerPage.service

import android.content.Intent
import android.net.VpnService
import android.os.IBinder
import timber.log.Timber

/**
 * VPN service implementing DNS-based content blocking.
 *
 * Phase 3 — full implementation:
 *  - Reads selected DNS preset from vpn_custom_dns table
 *  - Establishes VPN tunnel with addDnsServer() + addAllowedApplication() for whitelisted apps
 *  - Foreground notification (configurable via VPN_NOTIFICATION_CUSTOM_MESSAGE)
 *
 * Phase 1: stub.
 */
class MyVpnService : VpnService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VPN service start command")
        // TODO Phase 3
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        // VpnService requires onBind to handle VpnService.prepare() result
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.w("VPN service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        Timber.w("VPN revoked by system")
    }
}
