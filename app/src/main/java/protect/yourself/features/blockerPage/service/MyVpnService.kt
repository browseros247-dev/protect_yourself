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
import androidx.core.content.ContextCompat
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

    /**
     * Update [vpnState] AND mirror the new value to the companion-level
     * [observableVpnState] so the UI can read the actual service state.
     * BUG-06 fix: the UI previously only saw the DB-persisted VPN_SWITCH
     * (which can be out of sync — see BUG-02), not the live service state.
     *
     * Use this method instead of assigning `vpnState = ...` directly so the
     * companion-level mirror is always in sync.
     */
    private fun setVpnState(state: VpnState) {
        vpnState = state
        observableVpnState = state
    }

    enum class VpnState { IDLE, CONNECTING, CONNECTED, FAILED }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VPN service start command: action=${intent?.action}")

        // FIX 1.4: call startForeground() synchronously with a placeholder
        // "Connecting…" notification BEFORE the async startVpn() coroutine.
        // This prevents the system from killing the service during the
        // startup window (DB reads + establish() can take 500ms+).
        //
        // BUG-01b fix: ACTION_STOP must also call startForeground() because
        // BUG-01 fix changed stop() to use startForegroundService() on API
        // 26+. The system requires any service started via
        // startForegroundService() to call startForeground() within 5
        // seconds, regardless of whether the service intends to stop
        // itself immediately. Without this, the system throws
        // ForegroundServiceDidNotStartInTimeException.
        if (intent?.action == ACTION_START ||
            intent?.action == ACTION_RESTART ||
            intent?.action == ACTION_STOP ||
            intent?.action == null
        ) {
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

        setVpnState(VpnState.CONNECTING)
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
                    setVpnState(VpnState.FAILED)
                    stopSelf()
                    return@launch
                }

                // 3. Get VPN whitelist apps (apps that bypass VPN)
                val whitelistPackages = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.VPN_WHITELIST_APPS.value)
                    .map { it.packageName }

                // Phase 3: Determine VPN mode based on scheduled block apps.
                // - DNS_FILTER mode (default): all apps routed through VPN for
                //   DNS filtering, except whitelisted apps. Uses addDisallowedApplication.
                // - PER_APP_BLOCK mode (when scheduledBlockApps is non-empty):
                //   ONLY scheduled apps are routed through VPN (their traffic is
                //   black-holed). All other apps bypass VPN → normal internet.
                //   Uses addAllowedApplication. DNS filtering is paused for
                //   non-scheduled apps while this mode is active.
                val scheduledApps = scheduledBlockApps
                val isPerAppBlockMode = scheduledApps.isNotEmpty()
                if (isPerAppBlockMode) {
                    Timber.i("VPN starting in PER_APP_BLOCK mode for ${scheduledApps.size} scheduled apps")
                } else {
                    Timber.i("VPN starting in DNS_FILTER mode (normal)")
                }

                // 4. Build VPN interface — NopoX-style: addDnsServer + allowBypass,
                //    NO addRoute, NO manual DNS forwarding loop.
                //
                // In PER_APP_BLOCK mode, addDnsServer is still called (required
                // by the VpnService.Builder API), but it only affects the apps
                // routed through the VPN (the scheduled apps whose traffic is
                // black-holed anyway).
                //
                // CRITICAL FIX: allowBypass() is NOT called in per-app-block mode.
                // allowBypass() lets apps explicitly request to bypass the VPN
                // via Network.setProcessDefaultNetwork(). In per-app-block mode,
                // we WANT to block the targeted apps — allowing bypass would
                // let them circumvent the block. In DNS-filter mode, allowBypass
                // is safe because all traffic is supposed to go through the VPN.
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(InetAddress.getByName(VPN_ADDRESS), VPN_PREFIX_LENGTH)
                    .addAddress(InetAddress.getByName("10.0.2.16"), VPN_PREFIX_LENGTH)
                    .addAddress(InetAddress.getByName("10.0.2.17"), VPN_PREFIX_LENGTH)
                    .addAddress(InetAddress.getByName("10.0.2.18"), VPN_PREFIX_LENGTH)
                    .addDnsServer(InetAddress.getByName(firstDns))
                    .addDnsServer(InetAddress.getByName(secondDns))
                    .setMtu(VPN_MTU)

                if (isPerAppBlockMode) {
                    // CRITICAL FIX: do NOT call allowBypass() in per-app-block mode.
                    // Only call it in DNS-filter mode (below).
                    if (protect.yourself.BuildConfig.DEBUG) {
                        Timber.w("DEBUG: PER_APP_BLOCK mode — NOT calling allowBypass() — ${scheduledApps.size} apps will be blocked: $scheduledApps")
                    }
                    // ROOT CAUSE FIX: addRoute() is REQUIRED in per-app-block mode.
                    // Without addRoute(), the VPN TUN interface has no routes —
                    // traffic from targeted apps doesn't actually enter the TUN,
                    // so they keep their normal internet (block doesn't work).
                    //
                    // addAllowedApplication(pkg) only controls WHICH apps are
                    // routed through the VPN. addRoute("0.0.0.0", 0) tells the
                    // system to route ALL IPv4 traffic through the TUN. Combined
                    // with addAllowedApplication, ONLY the targeted apps' traffic
                    // enters the TUN (other apps bypass VPN entirely).
                    //
                    // Since we don't have a packet forwarding loop, the traffic
                    // that enters the TUN is never forwarded → black-holed →
                    // the targeted apps get no internet. This is the same pattern
                    // used by NetGuard, Shelter, and other per-app firewalls.
                    try {
                        builder.addRoute(InetAddress.getByName("0.0.0.0"), 0)
                        if (protect.yourself.BuildConfig.DEBUG) {
                            Timber.w("DEBUG: PER_APP_BLOCK mode — added route 0.0.0.0/0 (black-hole for targeted apps)")
                        }
                    } catch (t: Throwable) {
                        Timber.e(t, "Failed to add IPv4 route for per-app-block mode")
                    }
                    // Also add IPv6 route for completeness
                    try {
                        builder.addRoute(InetAddress.getByName("::"), 0)
                        if (protect.yourself.BuildConfig.DEBUG) {
                            Timber.w("DEBUG: PER_APP_BLOCK mode — added route ::/0 (IPv6 black-hole)")
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to add IPv6 route for per-app-block mode (non-critical)")
                    }
                } else {
                    builder.allowBypass()
                }

                if (isPerAppBlockMode) {
                    // Phase 3: PER_APP_BLOCK mode — route ONLY scheduled apps
                    // through the VPN tunnel. Their traffic enters the TUN but
                    // is never forwarded (black hole) → no internet.
                    // All other apps bypass the VPN → normal internet.
                    for (pkg in scheduledApps) {
                        try {
                            builder.addAllowedApplication(pkg)
                            Timber.v("App routed through VPN (blocked): $pkg")
                        } catch (t: Throwable) {
                            Timber.w(t, "Failed to add allowed app to VPN: $pkg")
                        }
                    }
                    // In per-app-block mode, our own app is NOT in the allowed
                    // list, so it bypasses the VPN automatically. No need for
                    // addDisallowedApplication(packageName).
                } else {
                    // DNS_FILTER mode — existing behavior.
                    // Apply per-app routing: whitelisted apps bypass VPN
                    for (pkg in whitelistPackages) {
                        try {
                            builder.addDisallowedApplication(pkg)
                            Timber.v("App bypasses VPN: $pkg")
                        } catch (t: Throwable) {
                            Timber.w(t, "Failed to disallow app from VPN: $pkg")
                        }
                    }
                    // Always allow our own app to bypass VPN (so we can communicate)
                    try {
                        builder.addDisallowedApplication(packageName)
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to allow self from VPN")
                    }
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
                if (protect.yourself.BuildConfig.DEBUG) {
                    Timber.w("DEBUG startVpn: calling builder.establish() — mode=${if (isPerAppBlockMode) "PER_APP_BLOCK" else "DNS_FILTER"}")
                }
                vpnInterface = builder.establish()
                if (vpnInterface == null) {
                    Timber.e("Failed to establish VPN interface — user may have revoked permission")
                    if (protect.yourself.BuildConfig.DEBUG) {
                        Timber.w("DEBUG startVpn: establish() returned NULL — VPN permission may be revoked")
                    }
                    isStarting.set(false)
                    setVpnState(VpnState.FAILED)
                    // AUDIT FIX: Only clear VPN_SWITCH if the VPN was started by
                    // the user (not by scheduled blocking). If scheduledBlockApps
                    // is non-empty, the VPN was started for per-app-block mode —
                    // clearing VPN_SWITCH would incorrectly disable the user's
                    // DNS filtering preference. The scheduled block will retry
                    // on the next ScheduleCheckWorker cycle.
                    if (scheduledBlockApps.isEmpty()) {
                        switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                    } else {
                        Timber.w("VPN establish failed in per-app-block mode — NOT clearing VPN_SWITCH " +
                            "(scheduled block will retry). scheduledBlockApps=${scheduledBlockApps.size}")
                    }
                    stopSelf()
                    return@launch
                }

                // BUG-04 fix: post-establish staleness check. If a newer
                // restart was issued while we were inside establish() (which
                // can take 500ms+), stopVpn() would have reset isStarting to
                // false. Detect that here and tear down our stale establish()
                // result so the newer startVpn() can take over with the
                // updated DNS / connection type. Without this check, the VPN
                // would end up running with the OLD DNS while the UI shows
                // the NEW mode — a silent protection failure.
                if (!isStarting.get()) {
                    Timber.w("startVpn: stale establish() — a newer restart was requested during establish(), tearing down")
                    try { vpnInterface?.close() } catch (_: Throwable) {}
                    vpnInterface = null
                    isRunning = false
                    setVpnState(VpnState.IDLE)
                    // Do NOT call stopSelf() — the newer startVpn() will
                    // re-establish. Just return from this stale invocation.
                    return@launch
                }

                isRunning = true
                isStarting.set(false)
                setVpnState(VpnState.CONNECTED)

                if (protect.yourself.BuildConfig.DEBUG) {
                    Timber.w("DEBUG startVpn: VPN ESTABLISHED SUCCESSFULLY — mode=${if (isPerAppBlockMode) "PER_APP_BLOCK" else "DNS_FILTER"}, scheduledApps=${scheduledApps.size}")
                    if (isPerAppBlockMode) {
                        Timber.w("DEBUG startVpn: blocked apps = $scheduledApps")
                    }
                }

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
                setVpnState(VpnState.FAILED)
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
            // BUG-04 fix: reset isStarting so that a restart issued while a
            // previous startVpn() is still in-flight (e.g. establish() is
            // taking 500ms+) is NOT skipped by the AtomicBoolean guard in
            // startVpn(). Without this reset, the second startVpn() call
            // would see isStarting=true and return early, leaving the VPN
            // running with the OLD DNS after establish() from the first
            // call completes. The post-establish staleness check below
            // (also part of BUG-04 fix) catches the case where establish()
            // already returned by the time stopVpn() runs.
            isStarting.set(false)
            currentConnectionType = VpnConnectionTypeIdentifiers.OFF
            setVpnState(VpnState.IDLE)
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

        // BUG-02 fix: persist VPN_SWITCH=false SYNCHRONOUSLY before stopSelf().
        // The previous implementation launched a coroutine on serviceScope to
        // do this, but stopSelf() immediately triggers onDestroy() which
        // cancels serviceScope — the coroutine was being cancelled mid-DB
        // write, leaving VPN_SWITCH=true in the database while the VPN was
        // actually dead. This caused the UI to show "Connected" indefinitely
        // and VpnRestartWorker to repeatedly fail on every boot.
        //
        // runBlocking is safe here because:
        //  - onRevoke() is called on a system binder thread, not the main
        //    thread (no ANR risk from main-thread blocking).
        //  - The DB write is fast (~10ms) and the DB is already open.
        //  - The cost of blocking the binder thread for 10ms is far less
        //    than the cost of leaving the user's protection state silently
        //    out of sync.
        try {
            kotlinx.coroutines.runBlocking {
                val db = AppDatabase.getInstance(this@MyVpnService)
                SwitchStatusValues(db.switchStatusDao())
                    .storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
            }
            Timber.i("VPN_SWITCH set to false (revoked by system)")
        } catch (t: Throwable) {
            Timber.e(t, "Failed to sync VPN_SWITCH=false on revoke — DB may be out of sync")
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

        // BUG-06 fix: companion-level observable state. The instance-level
        // setVpnState() method mirrors vpnState here so the UI can read the
        // actual service state (IDLE / CONNECTING / CONNECTED / FAILED)
        // rather than the DB-persisted VPN_SWITCH (which can be out of sync
        // — see BUG-02 / BUG-06). This is a simple @Volatile var instead of
        // a StateFlow to keep the implementation simple and compatible with
        // the Kotlin 1.9 kapt fallback.
        @Volatile
        var observableVpnState: VpnState = VpnState.IDLE
            private set

        // ===== Scheduled App Restrictions (Phase 2 stubs — Phase 3 implements) =====

        /**
         * Apps that should have their internet blocked via VPN per-app-block mode.
         * When non-empty, the VPN switches from DNS-filter mode to per-app-block
         * mode (addAllowedApplication for these apps only — their traffic is
         * black-holed, all other apps use normal internet).
         *
         * Set by ScheduleEngine when a scheduled internet-block rule becomes active.
         */
        @Volatile
        var scheduledBlockApps: Set<String> = emptySet()
            private set

        /**
         * Called by ScheduleEngine when scheduled internet-block apps change.
         * Stores the set and restarts the VPN to apply the new per-app-block mode.
         *
         * Phase 3 implementation: if the VPN is not currently running but
         * scheduled internet blocking is needed, start it. If the VPN is
         * running, restart it to switch modes.
         *
         * AUDIT FIX: Check VpnService.prepare() before starting. If the user
         * hasn't granted VPN permission, we CANNOT start the VPN from a
         * background context (AlarmManager/WorkManager). In that case, log
         * a warning + show a notification prompting the user to open the app
         * and grant permission. The schedule will retry on the next
         * ScheduleCheckWorker cycle (15 min).
         */
        fun setScheduledBlockApps(context: Context, apps: Set<String>) {
            scheduledBlockApps = apps
            Timber.i("MyVpnService: scheduledBlockApps updated (${apps.size} apps)")
            if (protect.yourself.BuildConfig.DEBUG) {
                Timber.w("DEBUG setScheduledBlockApps: apps=$apps")
                Timber.w("DEBUG setScheduledBlockApps: isRunning=${isRunning()}, instance=${instance != null}")
            }
            if (apps.isNotEmpty()) {
                // AUDIT FIX: Check VPN permission before starting.
                val prepareResult = android.net.VpnService.prepare(context)
                if (protect.yourself.BuildConfig.DEBUG) {
                    Timber.w("DEBUG setScheduledBlockApps: VpnService.prepare() = ${if (prepareResult != null) "NON_NULL(permission needed)" else "NULL(permission granted)"}")
                }
                if (prepareResult != null) {
                    Timber.w("MyVpnService: VPN permission not granted — cannot start per-app-block mode from background. " +
                        "User must open the app and enable VPN manually.")
                    try {
                        protect.yourself.commons.utils.notificationUtils.NotificationHelper
                            .showVpnPermissionRequiredNotification(context)
                    } catch (t: Throwable) {
                        Timber.w(t, "Failed to show VPN permission notification")
                    }
                    return
                }
                // Need VPN in per-app-block mode. Start or restart.
                if (!isRunning()) {
                    Timber.i("MyVpnService: VPN not running — starting in per-app-block mode")
                    if (protect.yourself.BuildConfig.DEBUG) {
                        Timber.w("DEBUG setScheduledBlockApps: calling start(context) — VPN will start in PER_APP_BLOCK mode")
                    }
                    start(context)
                } else {
                    Timber.i("MyVpnService: VPN running — restarting to switch to per-app-block mode")
                    if (protect.yourself.BuildConfig.DEBUG) {
                        Timber.w("DEBUG setScheduledBlockApps: calling restart(context) — VPN will switch to PER_APP_BLOCK mode")
                    }
                    restart(context)
                }
            }
        }

        /**
         * Called by ScheduleEngine when all scheduled internet-block rules end.
         * Clears the set and restarts the VPN in normal DNS-filter mode.
         *
         * AUDIT FIX: Only restart if the VPN was actually in per-app-block mode
         * (scheduledBlockApps was non-empty). Also check if the user's VPN_SWITCH
         * is on — if it is, restart in DNS-filter mode. If not, stop the VPN
         * entirely (it was only running for scheduled blocking).
         */
        fun clearScheduledBlockApps(context: Context) {
            val wasPerAppBlockMode = scheduledBlockApps.isNotEmpty()
            scheduledBlockApps = emptySet()
            if (wasPerAppBlockMode) {
                // The VPN was running in per-app-block mode. Check if the user
                // also wants DNS filtering (VPN_SWITCH=true). If so, restart
                // in DNS-filter mode. If not, stop the VPN.
                kotlinx.coroutines.runBlocking {
                    try {
                        val db = AppDatabase.getInstance(context)
                        val switchValues = SwitchStatusValues(db.switchStatusDao())
                        val vpnSwitchOn = switchValues.isVpnSwitchOn()
                        if (vpnSwitchOn) {
                            Timber.i("MyVpnService: scheduledBlockApps cleared — restarting VPN in DNS-filter mode (VPN_SWITCH is ON)")
                            restart(context)
                        } else {
                            Timber.i("MyVpnService: scheduledBlockApps cleared — stopping VPN (VPN_SWITCH is OFF)")
                            stop(context)
                        }
                    } catch (t: Throwable) {
                        Timber.w(t, "MyVpnService: failed to check VPN_SWITCH in clearScheduledBlockApps — defaulting to restart")
                        restart(context)
                    }
                }
            }
        }

        fun start(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_START
            }
            // BUG-01 fix: use startForegroundService() on API 26+ so the
            // system knows to enforce the 5-second startForeground() deadline.
            // Without this, startService() throws IllegalStateException on
            // Android 8+ when the app is in the background (e.g. when called
            // from VpnRestartWorker after a reboot), which means the VPN
            // silently fails to auto-restart after boot. NopoX 1.0.53 does
            // this correctly via VpnServiceUtils.vpnServiceAction().
            startForegroundServiceCompat(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_STOP
            }
            // BUG-01 fix: see start() above for rationale.
            // Note: onStartCommand handles ACTION_STOP by calling
            // startForegroundCompat() early (BUG-01b fix) before stopSelf(),
            // satisfying the 5-second deadline.
            startForegroundServiceCompat(context, intent)
        }

        fun restart(context: Context) {
            val intent = Intent(context, MyVpnService::class.java).apply {
                action = ACTION_RESTART
            }
            // BUG-01 fix: see start() above for rationale.
            startForegroundServiceCompat(context, intent)
        }

        /**
         * Helper: starts the VPN service using the correct API for the
         * Android version. On API 26+ uses
         * [ContextCompat.startForegroundService] (which internally calls
         * [Context.startForegroundService]); below API 26 uses
         * [Context.startService] (because startForegroundService does not
         * exist on older APIs).
         *
         * The caller MUST ensure that [MyVpnService.onStartCommand] calls
         * [MyVpnService.startForegroundCompat] within 5 seconds when invoked
         * via this method, otherwise the system throws
         * ForegroundServiceDidNotStartInTimeException on API 26+.
         */
        private fun startForegroundServiceCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(context, intent)
                } else {
                    context.startService(intent)
                }
            } catch (t: Throwable) {
                // Last-resort: catch SecurityException / IllegalStateException
                // so a failure to start the service does not crash the caller
                // (typically the UI thread). The user will see the VPN toggle
                // remain OFF, which is correct feedback.
                Timber.e(t, "Failed to start VPN service (action=${intent.action})")
            }
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
