package protect.yourself.features.blockerPage.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.utils.BlockerPageUtils
import protect.yourself.features.mainActivityPage.MainActivity
import timber.log.Timber
import java.net.InetAddress

/**
 * MyVpnService — DNS-blocking VPN service.
 *
 * Ported from original `MyVpnService.kt`.
 *
 * Behavior:
 *  - Reads selected DNS preset from vpn_custom_dns table
 *  - Establishes VPN tunnel with addDnsServer() for chosen DNS
 *  - Allows whitelisted apps to bypass VPN (per vpn_whitelist_apps)
 *  - Runs as foreground service (Android 8+ requirement)
 *  - Supports always-on VPN
 *
 * Phase 3: full DNS blocking implementation.
 *  - Reads DNS from DB
 *  - Establishes VPN interface
 *  - Shows foreground notification (configurable message)
 */
class MyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VPN service start command: intent=$intent")

        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            else -> startVpn()  // default behavior on boot
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

                // 1. Get selected DNS preset
                val dnsPreset = db.vpnCustomDnsDao().getSelected()
                if (dnsPreset == null) {
                    Timber.e("No DNS preset selected — cannot start VPN")
                    stopSelf()
                    return@launch
                }

                // Validate DNS addresses
                val utils = BlockerPageUtils.getInstance()
                if (!utils.isValidDNS(dnsPreset.firstDns) || !utils.isValidDNS(dnsPreset.secondDns)) {
                    Timber.e("Invalid DNS addresses: ${dnsPreset.firstDns}, ${dnsPreset.secondDns}")
                    stopSelf()
                    return@launch
                }

                // 2. Get VPN whitelist apps (apps that bypass VPN)
                val whitelistPackages = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.VPN_WHITELIST_APPS.value)
                    .map { it.packageName }

                // 3. Build VPN interface
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                    .addRoute(VPN_ROUTE, VPN_ROUTE_PREFIX)
                    .addDnsServer(InetAddress.getByName(dnsPreset.firstDns))
                    .addDnsServer(InetAddress.getByName(dnsPreset.secondDns))
                    .setMtu(VPN_MTU)

                // Apply per-app routing:
                // - If whitelist is non-empty, those apps ALLOWED to bypass VPN
                // - Use addAllowedApplication() to restrict which apps go through VPN
                // - Apps not in whitelist will have their DNS routed through our DNS
                for (pkg in whitelistPackages) {
                    try {
                        builder.addDisallowedApplication(pkg)
                        Timber.v("App disallowed from VPN: $pkg")
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to disallow app from VPN: $pkg")
                    }
                }

                // 4. Establish the VPN interface
                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    Timber.e("Failed to establish VPN interface — user may have revoked permission")
                    stopSelf()
                    return@launch
                }

                isRunning = true

                // 5. Get notification message (custom or default)
                val isHideNotification = switchValues.isVpnNotificationHideSwitchOn()
                val customMessage = switchValues.getVpnNotificationCustomMessage()
                val notificationText = if (!customMessage.isNullOrBlank()) customMessage
                    else getString(R.string.vpn_notification_text)

                // 6. Start foreground service
                val notification = buildNotification(notificationText, isHideNotification)
                startForeground(NOTIFICATION_ID, notification)

                Timber.i("VPN started with DNS ${dnsPreset.firstDns}, ${dnsPreset.secondDns}")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to start VPN")
                stopSelf()
            }
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            isRunning = false
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

        val displayText = if (hideContent) getString(R.string.app_name) else text

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(displayText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_focus, getString(R.string.stop_me), stopPending)
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
        stopSelf()
        Timber.w("VPN revoked by system")
    }

    companion object {
        const val ACTION_START = "protect_yourself.action.VPN_START"
        const val ACTION_STOP = "protect_yourself.action.VPN_STOP"
        const val NOTIFICATION_ID = 1001
        const val NOTIFICATION_CHANNEL_ID = "vpn_service_channel"

        // VPN tunnel config (matches original)
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_ROUTE = "0.0.0.0"
        private const val VPN_ROUTE_PREFIX = 0
        private const val VPN_MTU = 1500

        /**
         * Convenience method to start the VPN service.
         * Caller must have already called VpnService.prepare() and received RESULT_OK.
         */
        fun start(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        /**
         * Convenience method to stop the VPN service.
         */
        fun stop(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
