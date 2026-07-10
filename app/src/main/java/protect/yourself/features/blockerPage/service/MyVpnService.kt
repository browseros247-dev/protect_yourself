package protect.yourself.features.blockerPage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.identifiers.VpnConnectionTypeIdentifiers
import protect.yourself.features.blockerPage.utils.BlockerPageUtils
import protect.yourself.features.blockerPage.utils.DefaultDnsPresets
import protect.yourself.features.mainActivityPage.MainActivity
import timber.log.Timber
import java.net.InetAddress

/**
 * MyVpnService — NopoX-style VPN service.
 *
 * Implements the same VPN mechanism as the original NopoX:
 *
 * 1. VPN Connection Types:
 *    - NORMAL: Cloudflare Family DNS (1.1.1.3 / 1.0.0.3)
 *    - POWERFUL: AdGuard Family DNS (94.140.14.15 / 94.140.15.16)
 *    - CUSTOM: User-selected DNS preset from vpn_custom_dns table
 *
 * 2. Full traffic routing through VPN tunnel (addRoute 0.0.0.0/0)
 *    — all traffic goes through VPN, DNS queries use the selected family DNS
 *
 * 3. Per-app routing:
 *    - Whitelisted apps bypass VPN (addDisallowedApplication)
 *    - All other apps go through VPN tunnel
 *
 * 4. Always-on VPN support (declared in manifest)
 *
 * 5. Self-restart on revocation (updates switch + can restart)
 *
 * 6. Boot persistence (AppSystemActionReceiverAllTime restarts VPN after reboot)
 *
 * 7. Foreground service with configurable notification
 */
class MyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var currentConnectionType = VpnConnectionTypeIdentifiers.OFF
    private var currentFirstDns: String = ""
    private var currentSecondDns: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VPN service start command: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            ACTION_RESTART -> {
                stopVpn()
                startVpn()
            }
            else -> startVpn()
        }

        return START_STICKY
    }

    private fun startVpn() {
        if (isRunning) {
            Timber.w("VPN already running — ignoring start request")
            return
        }

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                // 1. Determine VPN connection type from DB
                val connectionTypeRaw = db.switchStatusDao().get("vpn_connection_type")?.asString()
                currentConnectionType = VpnConnectionTypeIdentifiers.fromString(connectionTypeRaw)

                if (currentConnectionType == VpnConnectionTypeIdentifiers.OFF) {
                    // Default to NORMAL if switch is ON but type not set
                    currentConnectionType = VpnConnectionTypeIdentifiers.NORMAL
                }

                // 2. Select DNS based on connection type
                // Note: NORMAL (Cloudflare Family 1.1.1.3) and POWERFUL (AdGuard
                // Family 94.140.14.15) both enforce SafeSearch at the DNS level
                // for Google, Bing, YouTube, and DuckDuckGo. This provides a
                // second independent layer of SafeSearch enforcement alongside
                // the accessibility-level URL redirect.
                val (firstDns, secondDns) = when (currentConnectionType) {
                    VpnConnectionTypeIdentifiers.NORMAL -> {
                        DefaultDnsPresets.CLOUDFLARE_FAMILY.firstDns to
                        DefaultDnsPresets.CLOUDFLARE_FAMILY.secondDns
                    }
                    VpnConnectionTypeIdentifiers.POWERFUL -> {
                        DefaultDnsPresets.ADGUARD_FAMILY.firstDns to
                        DefaultDnsPresets.ADGUARD_FAMILY.secondDns
                    }
                    VpnConnectionTypeIdentifiers.CUSTOM -> {
                        val dnsPreset = db.vpnCustomDnsDao().getSelected()
                        if (dnsPreset == null) {
                            Timber.e("No custom DNS preset selected — falling back to NORMAL")
                            DefaultDnsPresets.CLOUDFLARE_FAMILY.firstDns to
                            DefaultDnsPresets.CLOUDFLARE_FAMILY.secondDns
                        } else {
                            dnsPreset.firstDns to dnsPreset.secondDns
                        }
                    }
                    VpnConnectionTypeIdentifiers.OFF -> {
                        DefaultDnsPresets.CLOUDFLARE_FAMILY.firstDns to
                        DefaultDnsPresets.CLOUDFLARE_FAMILY.secondDns
                    }
                }

                currentFirstDns = firstDns
                currentSecondDns = secondDns

                // Validate DNS addresses
                val utils = BlockerPageUtils.getInstance()
                if (!utils.isValidDNS(firstDns) || !utils.isValidDNS(secondDns)) {
                    Timber.e("Invalid DNS addresses: $firstDns, $secondDns")
                    stopSelf()
                    return@launch
                }

                // 3. Get VPN whitelist apps (apps that bypass VPN)
                val whitelistPackages = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.VPN_WHITELIST_APPS.value)
                    .map { it.packageName }

                // 4. Build VPN interface — NopoX-style: full traffic routing
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                    .addRoute(VPN_ROUTE, VPN_ROUTE_PREFIX)  // Route ALL traffic through VPN
                    .addDnsServer(InetAddress.getByName(firstDns))
                    .addDnsServer(InetAddress.getByName(secondDns))
                    .setMtu(VPN_MTU)

                // Add search domain for DNS resolution
                builder.addSearchDomain(".")

                // 5. Apply per-app routing: whitelisted apps bypass VPN
                for (pkg in whitelistPackages) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        Timber.v("App bypasses VPN: $pkg")
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to disallow app from VPN: $pkg")
                    }
                }

                // 6. Always allow our own app to bypass VPN (so we can communicate)
                try {
                    builder.addDisallowedApplication(packageName)
                } catch (t: Throwable) {
                    Timber.w(t, "Failed to allow self from VPN")
                }

                // 7. Establish the VPN interface
                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    Timber.e("Failed to establish VPN interface — user may have revoked permission")
                    // Update switch to OFF
                    switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                    stopSelf()
                    return@launch
                }

                isRunning = true

                // 8. Get notification message (custom or default)
                val isHideNotification = switchValues.isVpnNotificationHideSwitchOn()
                val customMessage = switchValues.getVpnNotificationCustomMessage()
                val typeLabel = when (currentConnectionType) {
                    VpnConnectionTypeIdentifiers.NORMAL -> "Normal"
                    VpnConnectionTypeIdentifiers.POWERFUL -> "Powerful"
                    VpnConnectionTypeIdentifiers.CUSTOM -> "Custom"
                    VpnConnectionTypeIdentifiers.OFF -> ""
                }
                val notificationText = if (!customMessage.isNullOrBlank()) {
                    "$customMessage ($typeLabel)"
                } else {
                    "${getString(R.string.vpn_notification_text)} ($typeLabel)"
                }

                // 9. Start foreground service
                val notification = buildNotification(notificationText, isHideNotification)
                startForegroundCompat(notification)

                Timber.i("VPN started: type=$currentConnectionType DNS=$firstDns,$secondDns")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to start VPN")
                stopSelf()
            }
        }
    }

    /**
     * Start foreground service with correct API level handling.
     */
    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            isRunning = false
            currentConnectionType = VpnConnectionTypeIdentifiers.OFF
            Timber.i("VPN stopped")
        } catch (t: Throwable) {
            Timber.w(t, "Error stopping VPN")
        }
    }

    private fun buildNotification(text: String, hideContent: Boolean): Notification {
        createNotificationChannel()

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, MyVpnService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val restartIntent = Intent(this, MyVpnService::class.java).apply {
            action = ACTION_RESTART
        }
        val restartPending = PendingIntent.getService(
            this, 2, restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val displayText = if (hideContent) getString(R.string.app_name) else text

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(displayText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_focus, getString(R.string.vpn_stop), stopPending)
            .addAction(R.drawable.ic_focus, "Restart", restartPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.vpn_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_notification_channel)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
        Timber.w("VPN service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        // System revoked VPN (user toggled VPN off in system settings)
        stopVpn()

        // Update the switch in DB
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                SwitchStatusValues(db.switchStatusDao())
                    .storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            } catch (_: Throwable) {}
        }

        // NopoX-style: attempt self-restart if VPN switch is still ON
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                val switchValues = SwitchStatusValues(db.switchStatusDao())
                // Wait a moment before checking
                kotlinx.coroutines.delay(2000)
                if (switchValues.isVpnSwitchOn()) {
                    Timber.i("VPN was revoked but switch is still ON — attempting restart")
                    startVpn()
                }
            } catch (_: Throwable) {}
        }

        stopSelf()
        Timber.w("VPN revoked by system")
    }

    companion object {
        const val ACTION_START = "protect_yourself.action.VPN_START"
        const val ACTION_STOP = "protect_yourself.action.VPN_STOP"
        const val ACTION_RESTART = "protect_yourself.action.VPN_RESTART"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"

        // VPN tunnel config — NopoX-style: full traffic routing
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_ROUTE_PREFIX = 0
        private const val VPN_MTU = 1500

        /**
         * Start the VPN service.
         * Caller must have already called VpnService.prepare() and received RESULT_OK.
         */
        fun start(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        /**
         * Stop the VPN service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Restart the VPN service (used when DNS settings change).
         */
        fun restart(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            context.startService(intent)
        }

        /**
         * Check if VPN is currently running.
         */
        fun isRunning(): Boolean = instance?.isRunning ?: false

        @Volatile
        var instance: MyVpnService? = null
            private set
    }

    init {
        instance = this
    }
}
