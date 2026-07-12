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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import protect.yourself.R
import protect.yourself.core.appCoroutineScope
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
 * MyVpnService — DNS-filtering VPN service (NopoX-style).
 *
 * ROOT CAUSE OF "VPN connected but no filtering":
 * The previous implementation tried to be smarter than Android by routing DNS
 * server IPs into the TUN interface (`addRoute(dns, 32)`) and running a manual
 * DNS forwarding loop (read packets from TUN → parse IP/UDP → forward via
 * `protect()`-ed DatagramSocket → rebuild response packet → write back to TUN).
 * This approach is fragile:
 *
 * 1. `addRoute(dns, 32)` creates a routing conflict: the route says "send DNS
 *    to TUN", but `protect()` says "bypass TUN". On some Android versions the
 *    route wins → the forwarded query loops back into the TUN → black hole.
 * 2. The manual IP/UDP packet parsing + reconstruction is error-prone. A single
 *    off-by-one in the header offset means the response is malformed and the
 *    client drops it → "DNS probe finished NXDOMAIN".
 * 3. The `DNS_HIJACK_HOSTS` list adds 31 more /32 routes, each of which
 *    intercepts traffic to a different public DNS IP. The forwarder then has
 *    to rewrite source/dest IPs for each hijacked query — more complexity,
 *    more failure modes.
 *
 * THE FIX (NopoX approach — proven, simple, reliable):
 *
 * NopoX's decompiled `MyVpnService` (v1.0.53) is only 139 lines. It does NOT
 * call `addRoute` at all. It does NOT have a DNS forwarding loop. It simply:
 *   1. Calls `addDnsServer(dns1)` + `addDnsServer(dns2)` — tells Android to
 *      use the family-safe DNS servers for the VPN interface.
 *   2. Calls `allowBypass()` — lets apps that explicitly request it bypass
 *      the VPN (required for some system services).
 *   3. Calls `addDisallowedApplication(pkg)` for whitelisted apps.
 *   4. Calls `establish()`.
 *
 * Android's VPN framework handles the rest: when apps make DNS queries, the
 * system resolver sends them to the configured DNS servers (1.1.1.3 for
 * Cloudflare Family, 94.140.14.15 for AdGuard Family). The DNS servers do
 * the actual filtering — Cloudflare Family blocks adult content + malware,
 * AdGuard Family blocks ads + adult content + trackers. No manual forwarding
 * needed.
 *
 * This is the Android-recommended DNS-filtering VPN pattern. It's what
 * NopoX, Blokada, and DNS66 (in "system VPN" mode) all use.
 *
 * Connection modes (UI labels live in strings.xml + BlockerPageViewModel):
 *  - NORMAL    → "Balanced"  : Cloudflare Family (1.1.1.3 / 1.0.0.3)
 *  - POWERFUL  → "Strict"    : AdGuard Family (94.140.14.15 / 94.140.15.16)
 *  - CUSTOM    → "Custom DNS": user-selected preset from vpn_custom_dns table
 */
class MyVpnService : VpnService() {

    private val serviceScope = appCoroutineScope(
        scopeName = "MyVpnService",
        dispatcher = kotlinx.coroutines.Dispatchers.IO,
        context = this
    )
    private var vpnInterface: ParcelFileDescriptor? = null
    // FIX 1.3 + BUG-01: @Volatile for cross-thread visibility on Dispatchers.IO.
    // isStarting uses AtomicBoolean for atomic check-and-set to prevent
    // two concurrent startVpn() calls from both calling establish().
    @Volatile private var isRunning = false
    private val isStarting = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var currentConnectionType = VpnConnectionTypeIdentifiers.OFF
    @Volatile
    private var currentFirstDns: String = ""
    @Volatile
    private var currentSecondDns: String = ""

    @Volatile
    private var restartJob: kotlinx.coroutines.Job? = null

    // NOTE: an earlier "FIX 1.5" refactor removed the setter's
    // refreshNotification() call (it posted a notification via
    // NotificationManager.notify() BEFORE startForeground() tied the
    // notification ID to the foreground service, causing a brief
    // "notification posted by a non-foreground service" warning on
    // Android 14+). The intent was to call refreshNotification()
    // explicitly after startForeground() — but that call site was
    // never added, and the function itself has now been removed as
    // dead code. If the VPN notification needs to be updated after
    // config changes (custom message, hide toggle, connection type),
    // call startForeground(NOTIFICATION_ID, buildNotification(...))
    // again with the new notification — that is the Android-blessed
    // way to update a foreground service's notification.
    @Volatile private var vpnState: VpnState = VpnState.IDLE

    enum class VpnState { IDLE, CONNECTING, CONNECTED, FAILED }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VPN service start command: action=${intent?.action}")

        // FIX 1.4: call startForeground() synchronously with a placeholder
        // "Connecting…" notification BEFORE the async startVpn() coroutine.
        // This prevents the system from killing the service during the
        // startup window (DB reads + establish() can take 500ms+).
        if (intent?.action == ACTION_START || intent?.action == ACTION_RESTART || intent?.action == null) {
            try {
                val placeholderNotif = buildNotification(
                    getString(R.string.vpn_notification_text),
                    false
                )
                startForegroundCompat(placeholderNotif)
            } catch (t: Throwable) {
                Timber.w(t, "Failed to call startForeground early — continuing anyway")
            }
        }

        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                // FIX 1.1: persist VPN_SWITCH=false so the UI and boot
                // receiver know the user explicitly stopped the VPN.
                // Without this, the DB still says VPN is ON → the toggle
                // shows ON while no service is running, and on next reboot
                // the VPN auto-restarts against the user's stop intent.
                serviceScope.launch {
                    try {
                        val db = AppDatabase.getInstance(this@MyVpnService)
                        SwitchStatusValues(db.switchStatusDao())
                            .storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                        Timber.i("VPN_SWITCH set to false (user stopped via notification)")
                    } catch (t: Throwable) {
                        Timber.e(t, "Failed to sync VPN_SWITCH=false on stop")
                    }
                }
                stopVpn()
                stopSelf()
            }
            ACTION_RESTART -> {
                // FIX 1.2: assign the restart coroutine to restartJob so
                // stopVpn() can cancel it if the user taps Stop during the
                // 300ms restart window.
                stopVpn()
                restartJob = serviceScope.launch {
                    kotlinx.coroutines.delay(300)
                    startVpn()
                }
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
        // BUG-01 fix: AtomicBoolean.compareAndSet makes the check-and-set atomic.
        // Two concurrent calls cannot both pass this guard.
        if (!isStarting.compareAndSet(false, true)) {
            Timber.w("VPN start already in progress — ignoring duplicate start request")
            return
        }

        vpnState = VpnState.CONNECTING
        protect.yourself.core.ProtectYourselfApp.getCrashLogger()
            ?.logBreadcrumb("VpnService", "startVpn requested")
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                val switchValues = SwitchStatusValues(db.switchStatusDao())

                // 1. Determine VPN connection type from DB
                val connectionTypeRaw = db.switchStatusDao()
                    .get(SwitchIdentifier.VPN_CONNECTION_TYPE)?.asString()
                currentConnectionType = VpnConnectionTypeIdentifiers.fromString(connectionTypeRaw)

                if (currentConnectionType == VpnConnectionTypeIdentifiers.OFF) {
                    currentConnectionType = VpnConnectionTypeIdentifiers.NORMAL
                }

                // 2. Select DNS based on connection type.
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
                    isStarting.set(false)
                    vpnState = VpnState.FAILED
                    stopSelf()
                    return@launch
                }

                // 3. Get VPN whitelist apps (apps that bypass VPN)
                val whitelistPackages = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.VPN_WHITELIST_APPS.value)
                    .map { it.packageName }

                // 4. Build VPN interface — NopoX-style: addDnsServer + allowBypass,
                //    NO addRoute, NO manual DNS forwarding loop.
                //
                // Android's VPN framework handles DNS routing automatically:
                // when apps make DNS queries, the system resolver sends them to
                // the DNS servers configured via addDnsServer(). The DNS servers
                // (Cloudflare Family / AdGuard Family) do the actual content
                // filtering. No manual packet forwarding needed.
                //
                // This is the same approach NopoX v1.0.53 uses (decompiled):
                //   builder.addDnsServer(dns1)
                //   builder.addDnsServer(dns2)
                //   builder.allowBypass()
                //   builder.establish()
                // No addRoute, no FileInputStream, no DatagramSocket, no protect().
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(InetAddress.getByName(VPN_ADDRESS), VPN_PREFIX_LENGTH)
                    // NopoX adds multiple private addresses — we match its pattern.
                    .addAddress(InetAddress.getByName("10.0.2.16"), VPN_PREFIX_LENGTH)
                    .addAddress(InetAddress.getByName("10.0.2.17"), VPN_PREFIX_LENGTH)
                    .addAddress(InetAddress.getByName("10.0.2.18"), VPN_PREFIX_LENGTH)
                    .addDnsServer(InetAddress.getByName(firstDns))
                    .addDnsServer(InetAddress.getByName(secondDns))
                    .setMtu(VPN_MTU)
                    .allowBypass()

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

                // 7. Set configure intent — opens MainActivity when user taps
                //    the VPN gear icon in system VPN settings. NopoX does this too.
                try {
                    val configIntent = Intent(this@MyVpnService, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                    }
                    val configPending = PendingIntent.getActivity(
                        this@MyVpnService, 0, configIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    builder.setConfigureIntent(configPending)
                } catch (t: Throwable) {
                    Timber.w(t, "Failed to set configure intent")
                }

                // 8. Establish the VPN interface
                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    Timber.e("Failed to establish VPN interface — user may have revoked permission")
                    isStarting.set(false)
                    vpnState = VpnState.FAILED
                    switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                    stopSelf()
                    return@launch
                }

                isRunning = true
                isStarting.set(false)
                vpnState = VpnState.CONNECTED

                // 8. Get notification message (custom or default)
                val isHideNotification = switchValues.isVpnNotificationHideSwitchOn()
                val customMessage = switchValues.getVpnNotificationCustomMessage()
                val typeLabel = when (currentConnectionType) {
                    VpnConnectionTypeIdentifiers.NORMAL -> getString(R.string.vpn_mode_balanced_label)
                    VpnConnectionTypeIdentifiers.POWERFUL -> getString(R.string.vpn_mode_strict_label)
                    VpnConnectionTypeIdentifiers.CUSTOM -> getString(R.string.vpn_mode_custom_label)
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

                Timber.i("VPN started: type=$currentConnectionType DNS=$firstDns,$secondDns (NopoX-style DNS filtering)")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to start VPN")
                isStarting.set(false)
                vpnState = VpnState.FAILED
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                    throwable = t,
                    tag = "VpnService",
                    message = "Failed to start VPN",
                    extraContext = mapOf(
                        "connectionType" to currentConnectionType.name,
                        "firstDns" to currentFirstDns,
                        "secondDns" to currentSecondDns
                    )
                )
                stopSelf()
            }
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopVpn() {
        try {
            restartJob?.cancel()
            restartJob = null
            vpnInterface?.close()
            vpnInterface = null
            isRunning = false
            currentConnectionType = VpnConnectionTypeIdentifiers.OFF
            vpnState = VpnState.IDLE
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

        val titleRes = when (vpnState) {
            VpnState.CONNECTING -> R.string.vpn_notification_title_connecting
            VpnState.FAILED -> R.string.vpn_notification_title_failed
            VpnState.CONNECTED, VpnState.IDLE -> R.string.vpn_notification_title
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_focus)
            .setContentTitle(getString(titleRes))
            .setContentText(displayText)
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(R.drawable.ic_stop, getString(R.string.vpn_stop), stopPending)
            .addAction(R.drawable.ic_restart, getString(R.string.vpn_restart), restartPending)
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
        serviceScope.cancel()
        instance = null
        Timber.w("VPN service destroyed")
    }

    override fun onRevoke() {
        super.onRevoke()
        stopVpn()

        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                SwitchStatusValues(db.switchStatusDao())
                    .storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
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

        // VPN tunnel config — NopoX-style: only addDnsServer + allowBypass.
        // No addRoute, no DNS forwarding loop.
        private const val VPN_ADDRESS = "10.0.2.15"
        private const val VPN_PREFIX_LENGTH = 24
        private const val VPN_MTU = 1500

        fun start(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_START
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            context.startService(intent)
        }

        fun isRunning(): Boolean = instance?.isRunning ?: false

        @Volatile
        var instance: MyVpnService? = null
            private set
    }

    init {
        instance = this
    }
}
