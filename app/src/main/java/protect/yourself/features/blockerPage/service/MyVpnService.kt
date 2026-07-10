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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
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
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * MyVpnService — DNS-filtering VPN service (fixed).
 *
 * PREVIOUS BUG (caused "VPN breaks internet"):
 * The original implementation called `addRoute("0.0.0.0", 0)` which routes ALL
 * traffic (the entire IPv4 internet) into the VPN's TUN interface. But the
 * service never read from / forwarded those packets — the TUN was a black hole.
 * As soon as the VPN started, every packet (including DNS) disappeared and the
 * device lost all connectivity.
 *
 * FIX (DNS-only interception — same pattern used by AdGuard, Blokada, Intra,
 * DNS66, NopoX):
 *
 * 1. We do NOT route all traffic through the VPN. We only route the two DNS
 *    server IPs (`/32` each) so that DNS queries enter the TUN. Every other
 *    packet (browsing, streaming, apps) goes straight out through the device's
 *    normal network — full speed, no interruption.
 *
 * 2. We run a real DNS forwarding loop: read IP packets from the TUN, find the
 *    UDP DNS queries, open a `protect()`-ed DatagramSocket, forward the query
 *    to the upstream DNS server, read the response, and write it back to the
 *    TUN. `protect()` exempts the socket from the VPN routing so the forwarded
 *    query goes out through the real network.
 *
 * 3. The result: only DNS is intercepted (so we can enforce adult-content /
 *    ad-blocking DNS), everything else is untouched. Users keep full internet.
 *
 * Connection modes (UI labels live in strings.xml + BlockerPageViewModel):
 *  - NORMAL    → "Balanced"  : Cloudflare Family (1.1.1.3 / 1.0.0.3)
 *  - POWERFUL  → "Strict"    : AdGuard Family (94.140.14.15 / 94.140.15.16)
 *  - CUSTOM    → "Custom DNS": user-selected preset from vpn_custom_dns table
 */
class MyVpnService : VpnService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var isRunning = false
    private var isStarting = false  // guards against concurrent startVpn() calls
    private var currentConnectionType = VpnConnectionTypeIdentifiers.OFF
    private var currentFirstDns: String = ""
    private var currentSecondDns: String = ""

    /** Active DNS forwarding job — cancelled on stop / restart. */
    private var forwarderJob: Job? = null

    /**
     * Single pending restart job scheduled by [onRevoke]. VPN-04 fix: only one
     * restart attempt may be in flight at a time — cancel any existing restart
     * job before scheduling a new one. This prevents restart cascades when the
     * user toggles the VPN switch off/on while a self-restart is already pending.
     */
    private var restartJob: Job? = null

    /**
     * Current VPN state, used to keep the foreground notification title in sync
     * with reality (VPN-18 fix). Updated by [startVpn] / [stopVpn] / [onRevoke].
     */
    private var vpnState: VpnState = VpnState.IDLE
        set(value) {
            field = value
            // Refresh the notification so the title reflects the new state.
            // Best-effort — if the notification isn't built yet, this is a no-op.
            try { refreshNotification() } catch (_: Throwable) {}
        }

    enum class VpnState { IDLE, CONNECTING, CONNECTED, FAILED }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("VPN service start command: action=${intent?.action}")

        when (intent?.action) {
            ACTION_START -> startVpn()
            ACTION_STOP -> {
                stopVpn()
                stopSelf()
            }
            ACTION_RESTART -> {
                // Restart: stop VPN, wait for forwarder to fully cancel,
                // then start again. Without the delay, the old forwarder
                // coroutine may still be reading from the old vpnInterface
                // when the new one is established — causing a race condition.
                stopVpn()
                serviceScope.launch {
                    kotlinx.coroutines.delay(300)  // let old forwarder cancel
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
        if (isStarting) {
            Timber.w("VPN start already in progress — ignoring duplicate start request")
            return
        }
        isStarting = true

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
                    // Default to NORMAL if switch is ON but type not set
                    currentConnectionType = VpnConnectionTypeIdentifiers.NORMAL
                }

                // 2. Select DNS based on connection type.
                // NORMAL (Cloudflare Family 1.1.1.3) and POWERFUL (AdGuard Family
                // 94.140.14.15) both enforce SafeSearch at the DNS level for
                // Google, Bing, YouTube, and DuckDuckGo. This provides a second
                // independent layer of SafeSearch enforcement alongside the
                // accessibility-level URL redirect.
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

                val firstDnsAddr = InetAddress.getByName(firstDns)
                val secondDnsAddr = InetAddress.getByName(secondDns)

                // 3. Get VPN whitelist apps (apps that bypass VPN)
                val whitelistPackages = db.selectedAppsListDao()
                    .getSelectedByIdentifier(SelectedAppListIdentifier.VPN_WHITELIST_APPS.value)
                    .map { it.packageName }

                // 4. Build VPN interface — DNS-only interception.
                //
                // CRITICAL FIX: Do NOT addRoute("0.0.0.0", 0). That would route
                // the entire internet into the TUN, and since we are only a DNS
                // forwarder (not a full packet forwarder), all that traffic would
                // be lost — killing the user's internet.
                //
                // Instead, we route ONLY the DNS server IPs (/32). That way:
                //   - DNS queries (destined for the configured DNS servers) enter
                //     the TUN and get forwarded by our DNS loop below.
                //   - All other traffic (HTTPS, streaming, app data) bypasses the
                //     VPN entirely and goes straight through the device's normal
                //     network at full speed.
                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .addAddress(VPN_ADDRESS, VPN_PREFIX_LENGTH)
                    .addDnsServer(firstDnsAddr)
                    .addDnsServer(secondDnsAddr)
                    .setMtu(VPN_MTU)
                    // Route ONLY the two DNS server IPs through the TUN.
                    .addRoute(firstDns, 32)
                    .addRoute(secondDns, 32)

                // Also intercept the DNS-resolver well-known address ranges so
                // that hardcoded DNS lookups (e.g. 8.8.8.8, 1.1.1.1) used by
                // some browsers/apps are redirected through our filtered DNS.
                // This is the same trick Intra / DNS66 use: we route the common
                // public-DNS IPs into the TUN and our forwarder rewrites them
                // to the user-selected filtered DNS server.
                for (captured in DNS_HIJACK_HOSTS) {
                    try {
                        builder.addRoute(captured.first, captured.second)
                    } catch (t: Throwable) {
                        Timber.w(t, "Could not add DNS hijack route: ${captured.first}/${captured.second}")
                    }
                }

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
                    vpnState = VpnState.FAILED
                    switchValues.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, false)
                    stopSelf()
                    return@launch
                }

                isRunning = true
        isStarting = false
                vpnState = VpnState.CONNECTED

                // 8. Start the DNS forwarding loop (THE actual filtering work).
                //    Without this loop the TUN would just buffer DNS queries
                //    forever and the user would see "no internet".
                startDnsForwarder(firstDnsAddr, secondDnsAddr)

                // 9. Get notification message (custom or default)
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

                // 10. Start foreground service
                val notification = buildNotification(notificationText, isHideNotification)
                startForegroundCompat(notification)

                Timber.i("VPN started: type=$currentConnectionType DNS=$firstDns,$secondDns (DNS-only mode)")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to start VPN")
                isStarting = false
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

    /**
     * DNS forwarding loop.
     *
     * Reads IP packets from the TUN input stream. For each UDP packet destined
     * for port 53 (DNS), we:
     *   1. Acquire a `protect()`-ed DatagramSocket from the socket pool (VPN-02
     *      fix — prevents per-query FD exhaustion under load).
     *   2. Send the DNS query to the upstream DNS server (selected by the user's
     *      mode: Cloudflare Family / AdGuard Family / custom preset).
     *   3. Read the response and validate its transaction ID against the request
     *      (security fix — drops forged / mismatched responses).
     *   4. Rewrite the IP/UDP header so the response looks like it came from the
     *      original destination the client expected, then write it back to the
     *      TUN output stream.
     *
     * Performance: each query is launched on its own coroutine on a bounded
     * `limitedParallelism(8)` dispatcher, so up to 8 queries can be in flight
     * simultaneously. This prevents a single slow upstream from blocking all
     * subsequent queries (the old serial loop had a worst-case latency of
     * N × 5s when the upstream was unreachable).
     *
     * Non-DNS packets are dropped (they should not normally arrive because we
     * only route DNS IPs into the TUN, but we may receive packets destined for
     * hijacked DNS IPs like 8.8.8.8).
     */
    private fun startDnsForwarder(upstreamPrimary: InetAddress, upstreamSecondary: InetAddress) {
        val pfd = vpnInterface ?: return
        forwarderJob?.cancel()
        // Bounded parallelism: allow up to 8 concurrent DNS forwards. This
        // matches the typical browser concurrency (Chrome fires 6-8 parallel
        // DNS lookups per page load) without overwhelming the socket pool.
        val forwardDispatcher = Dispatchers.IO.limitedParallelism(8)
        val socketPool = DnsSocketPool(this@MyVpnService, maxPoolSize = 16)
        forwarderJob = serviceScope.launch {
            val input = FileInputStream(pfd.fileDescriptor)
            val output = FileOutputStream(pfd.fileDescriptor)
            val outputLock = Any()  // serialize writes to the TUN output stream
            val buffer = ByteBuffer.allocate(MAX_PACKET_SIZE)

            Timber.i("DNS forwarder started: primary=$upstreamPrimary secondary=$upstreamSecondary")
            try {
                while (isActive && !isClosed) {
                    buffer.clear()
                    val read = input.read(buffer.array())
                    if (read <= 0) continue

                    // Parse the IP header to find protocol + source/destination.
                    val packet = buffer.array().copyOfRange(0, read)
                    val parsed = parseIpPacket(packet) ?: continue
                    if (parsed.protocol != PROTO_UDP) continue

                    val udp = parseUdp(packet, parsed.headerLength) ?: continue
                    if (udp.destinationPort != DNS_PORT && udp.sourcePort != DNS_PORT) continue

                    // Extract the DNS payload.
                    val dnsPayload = packet.copyOfRange(
                        parsed.headerLength + UDP_HEADER_LENGTH,
                        parsed.headerLength + UDP_HEADER_LENGTH + udp.length - UDP_HEADER_LENGTH
                    )

                    // Launch a parallel coroutine for this query. The output
                    // write is serialized via outputLock so concurrent completions
                    // don't interleave packets in the TUN.
                    launch(forwardDispatcher) {
                        var response = forwardDnsQuery(socketPool, upstreamPrimary, dnsPayload)
                        if (response == null) {
                            Timber.w("DNS query to primary ($upstreamPrimary) failed — trying secondary ($upstreamSecondary)")
                            response = forwardDnsQuery(socketPool, upstreamSecondary, dnsPayload)
                        }
                        if (response == null) {
                            Timber.w("DNS query failed for both upstreams — dropping (payload=${dnsPayload.size}B)")
                            return@launch
                        }

                        // Security: validate the DNS response transaction ID
                        // against the request. A mismatched ID means the
                        // response is either forged or belongs to a different
                        // query — drop it.
                        if (!validateDnsTransactionId(dnsPayload, response)) {
                            Timber.w("DNS response transaction ID mismatch — dropping (payload=${dnsPayload.size}B resp=${response.size}B)")
                            return@launch
                        }

                        // Build the response IP+UDP packet addressed back to the
                        // original client. Source = the DNS server the client
                        // originally asked (so the client accepts the reply).
                        val responsePacket = buildDnsResponsePacket(
                            srcAddress = parsed.destinationAddress,
                            dstAddress = parsed.sourceAddress,
                            srcPort = DNS_PORT,
                            dstPort = udp.sourcePort,
                            dnsPayload = response
                        )
                        synchronized(outputLock) {
                            output.write(responsePacket)
                        }
                    }
                }
            } catch (t: Throwable) {
                if (isActive) {
                    Timber.w(t, "DNS forwarder loop error")
                }
            } finally {
                socketPool.close()
                Timber.i("DNS forwarder stopped")
            }
        }
    }

    /**
     * Forwards a single DNS payload to [upstream] using a socket from [pool]
     * and returns the response payload. Returns null on failure (timeout,
     * network error, or response exceeds [MAX_DNS_RESPONSE_SIZE]).
     */
    private fun forwardDnsQuery(
        pool: DnsSocketPool,
        upstream: InetAddress,
        payload: ByteArray
    ): ByteArray? {
        val socket = pool.acquire() ?: return null
        return try {
            val request = DatagramPacket(payload, payload.size, upstream, DNS_PORT)
            socket.send(request)

            val responseBuffer = ByteArray(MAX_DNS_RESPONSE_SIZE)
            val response = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(response)
            // Cap response size — a malicious or buggy upstream could send a
            // huge response; we only forward up to MAX_DNS_RESPONSE_SIZE.
            response.data.copyOfRange(0, minOf(response.length, MAX_DNS_RESPONSE_SIZE))
        } catch (t: Throwable) {
            Timber.w(t, "DNS forward failed to $upstream")
            // On failure, invalidate the socket (don't return it to the pool —
            // it may be in a bad state).
            pool.invalidate(socket)
            null
        } finally {
            // Only return the socket to the pool if the receive succeeded.
            // pool.acquire() / pool.release() handle the bookkeeping; if
            // invalidate() was called, release() is a no-op.
            pool.release(socket)
        }
    }

    /**
     * Validates that the DNS response's transaction ID matches the request's.
     * The transaction ID is the first 2 bytes of the DNS payload (RFC 1035).
     * A mismatch indicates a forged response or a response to a different query.
     */
    private fun validateDnsTransactionId(request: ByteArray, response: ByteArray): Boolean {
        if (request.size < 2 || response.size < 2) return false
        val reqId = ((request[0].toInt() and 0xFF) shl 8) or (request[1].toInt() and 0xFF)
        val respId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
        return reqId == respId
    }

    /**
     * A small pool of `protect()`-ed DatagramSockets, reused across DNS
     * queries. VPN-02 fix: opening a new DatagramSocket per query burns file
     * descriptors and can crash the service with EMFILE under load. The pool
     * caps the number of concurrent sockets and reuses them.
     *
     * Sockets are `protect()`-ed at creation time and never re-protected.
     * `protect()` is idempotent on a given socket — the OS remembers the
     * exemption for the lifetime of the socket.
     */
    private class DnsSocketPool(
        private val vpnService: VpnService,
        private val maxPoolSize: Int = 16
    ) {
        private val pool = ArrayDeque<DatagramSocket>()
        private val lock = Any()
        private val created = java.util.concurrent.atomic.AtomicInteger(0)

        /** Acquires a socket from the pool, or creates a new one if the pool is empty and below cap. */
        fun acquire(): DatagramSocket? {
            synchronized(lock) {
                if (pool.isNotEmpty()) return pool.removeFirst()
                if (created.get() >= maxPoolSize) return null  // pool exhausted
            }
            return try {
                val socket = DatagramSocket()
                if (!vpnService.protect(socket)) {
                    Timber.w("protect() returned false — socket may loop back into VPN")
                }
                socket.soTimeout = DNS_TIMEOUT_MS
                created.incrementAndGet()
                socket
            } catch (t: Throwable) {
                Timber.w(t, "Failed to create DNS socket")
                null
            }
        }

        /** Returns a healthy socket to the pool. No-op if [socket] was invalidated. */
        fun release(socket: DatagramSocket) {
            // If invalidate() was called, the socket is already closed and the
            // counter was already decremented — nothing to do here.
            if (socket.isClosed) return
            synchronized(lock) {
                if (pool.size < maxPoolSize && !socket.isClosed) {
                    pool.addLast(socket)
                } else {
                    try { socket.close() } catch (_: Throwable) {}
                    created.decrementAndGet()
                }
            }
        }

        /** Marks [socket] as bad — closes it and decrements the counter so a new socket can be created. */
        fun invalidate(socket: DatagramSocket) {
            if (socket.isClosed) return  // already invalidated
            try { socket.close() } catch (_: Throwable) {}
            created.decrementAndGet()
        }

        /** Closes all sockets in the pool. Called when the forwarder stops. */
        fun close() {
            synchronized(lock) {
                while (pool.isNotEmpty()) {
                    try { pool.removeFirst().close() } catch (_: Throwable) {}
                }
                created.set(0)
            }
        }
    }

    // ===== Minimal IP/UDP packet helpers =====
    // We only need enough parsing to forward DNS — not a full network stack.

    private data class IpHeader(
        val headerLength: Int,
        val protocol: Int,
        val sourceAddress: InetAddress,
        val destinationAddress: InetAddress
    )

    private data class UdpHeader(
        val sourcePort: Int,
        val destinationPort: Int,
        val length: Int
    )

    private fun parseIpPacket(packet: ByteArray): IpHeader? {
        if (packet.size < 20) return null
        // Version + IHL
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return null // We only handle IPv4. IPv6 routes around VPN anyway.
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (packet.size < ihl) return null
        val protocol = packet[9].toInt() and 0xFF
        val src = InetAddress.getByAddress(packet.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        return IpHeader(ihl, protocol, src, dst)
    }

    private fun parseUdp(packet: ByteArray, ipHeaderLength: Int): UdpHeader? {
        if (packet.size < ipHeaderLength + UDP_HEADER_LENGTH) return null
        val off = ipHeaderLength
        val srcPort = ((packet[off].toInt() and 0xFF) shl 8) or (packet[off + 1].toInt() and 0xFF)
        val dstPort = ((packet[off + 2].toInt() and 0xFF) shl 8) or (packet[off + 3].toInt() and 0xFF)
        val length = ((packet[off + 4].toInt() and 0xFF) shl 8) or (packet[off + 5].toInt() and 0xFF)
        return UdpHeader(srcPort, dstPort, length)
    }

    /** Builds an IPv4/UDP response packet wrapping [dnsPayload]. */
    private fun buildDnsResponsePacket(
        srcAddress: InetAddress,
        dstAddress: InetAddress,
        srcPort: Int,
        dstPort: Int,
        dnsPayload: ByteArray
    ): ByteArray {
        val totalLength = 20 + UDP_HEADER_LENGTH + dnsPayload.size
        val out = ByteArray(totalLength)

        // IPv4 header — no options, IHL=5 (20 bytes).
        // VPN-03 fix: we MUST compute the IPv4 header checksum. Some OEM kernels
        // (notably certain MediaTek and Huawei builds) validate checksums on
        // packets entering the TUN and silently drop zero-checksum packets,
        // causing "DNS probe finished NXDOMAIN" errors. The Linux TUN driver
        // itself does not require checksums, but we cannot rely on the underlying
        // kernel path on all OEM ROMs.
        out[0] = 0x45.toByte() // version 4, IHL 5
        out[1] = 0x00.toByte() // DSCP/ECN
        out[2] = ((totalLength ushr 8) and 0xFF).toByte()
        out[3] = (totalLength and 0xFF).toByte()
        out[4] = 0x00; out[5] = 0x00 // identification
        out[6] = 0x40.toByte(); out[7] = 0x00 // flags=DF (don't fragment), fragment offset=0
        out[8] = 64.toByte()         // TTL
        out[9] = PROTO_UDP.toByte()  // protocol = UDP
        // checksum at [10..11] is filled in below (must be 0 during computation)
        out[10] = 0x00; out[11] = 0x00
        System.arraycopy(srcAddress.address, 0, out, 12, 4)
        System.arraycopy(dstAddress.address, 0, out, 16, 4)

        // Compute and write the IPv4 header checksum (16-bit one's complement
        // of the one's complement sum of the 20-byte header words).
        val checksum = computeIpChecksum(out, 0, 20)
        out[10] = ((checksum ushr 8) and 0xFF).toByte()
        out[11] = (checksum and 0xFF).toByte()

        // UDP header
        val off = 20
        out[off] = ((srcPort ushr 8) and 0xFF).toByte()
        out[off + 1] = (srcPort and 0xFF).toByte()
        out[off + 2] = ((dstPort ushr 8) and 0xFF).toByte()
        out[off + 3] = (dstPort and 0xFF).toByte()
        val udpLen = UDP_HEADER_LENGTH + dnsPayload.size
        out[off + 4] = ((udpLen ushr 8) and 0xFF).toByte()
        out[off + 5] = (udpLen and 0xFF).toByte()
        // UDP checksum is OPTIONAL for IPv4 (RFC 768). We leave it at 0 — the
        // receiver must accept a 0 checksum. Computing it would require a
        // pseudo-header (src IP + dst IP + protocol + UDP length) and is only
        // needed for IPv6, which we do not handle here.
        out[off + 6] = 0x00; out[off + 7] = 0x00

        // DNS payload
        System.arraycopy(dnsPayload, 0, out, off + UDP_HEADER_LENGTH, dnsPayload.size)
        return out
    }

    /**
     * Computes the IPv4 header checksum (RFC 1071) over [length] bytes of
     * [packet] starting at [offset]. The checksum field itself must be 0
     * during the computation. Returns the 16-bit checksum (host order).
     */
    private fun computeIpChecksum(packet: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        val end = offset + length
        var i = offset
        while (i + 1 < end) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            i += 2
        }
        // If the length is odd, pad the last byte with a zero byte.
        if (i < end) {
            sum += (packet[i].toInt() and 0xFF) shl 8
        }
        // Fold 32-bit sum into 16 bits.
        while ((sum ushr 16) != 0L) {
            sum = (sum and 0xFFFF) + (sum ushr 16)
        }
        return (sum.toInt().inv()) and 0xFFFF
    }

    private val isClosed: Boolean get() = vpnInterface?.fileDescriptor?.valid()?.not() ?: true

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
            forwarderJob?.cancel()
            forwarderJob = null
            restartJob?.cancel()
            restartJob = null
            vpnInterface?.close()
            vpnInterface = null
            isRunning = false
            isStarting = false
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

        // VPN-18 fix: notification title reflects actual state, not a static string.
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
            // VPN-13 fix: use distinct icons for Stop vs Restart so users can
            // tell them apart at a glance in the notification shade.
            .addAction(R.drawable.ic_stop, getString(R.string.vpn_stop), stopPending)
            .addAction(R.drawable.ic_restart, getString(R.string.vpn_restart), restartPending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Re-posts the foreground notification with the current [vpnState] and
     * notification text. Used by the [vpnState] setter so the notification
     * title stays in sync with reality (VPN-18 fix). No-op if the service
     * has not yet called startForeground() (i.e. before the VPN is established).
     *
     * Launches a coroutine on [serviceScope] because the notification text
     * depends on suspend DB reads (hide-notification flag + custom message).
     */
    private fun refreshNotification() {
        if (!isRunning && vpnState != VpnState.CONNECTING && vpnState != VpnState.FAILED) return
        serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                val switchValues = SwitchStatusValues(db.switchStatusDao())
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
                val notification = buildNotification(notificationText, isHideNotification)
                val nm = getSystemService(NotificationManager::class.java)
                nm.notify(NOTIFICATION_ID, notification)
            } catch (_: Throwable) {
                // Best-effort — if the DB isn't available, skip the refresh.
            }
        }
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
        // VPN-19 fix: clear the singleton so MyVpnService.isRunning() (companion)
        // does not return stale true after the service has been destroyed.
        instance = null
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

        // VPN-04 fix: schedule AT MOST ONE self-restart attempt. Cancel any
        // existing restart job before scheduling a new one — this prevents
        // restart cascades when the user toggles the VPN switch off/on while
        // a self-restart is already pending, or when onRevoke fires twice in
        // quick succession (which can happen if the system revokes and the
        // user re-grants in rapid succession).
        restartJob?.cancel()
        restartJob = serviceScope.launch {
            try {
                val db = AppDatabase.getInstance(this@MyVpnService)
                val switchValues = SwitchStatusValues(db.switchStatusDao())
                // Wait a moment before checking — gives the user time to
                // re-grant VPN permission if they accidentally revoked it.
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

        // VPN tunnel config — DNS-only interception mode.
        //
        // We give the TUN interface a private /32 address (10.0.0.2). We do NOT
        // addRoute("0.0.0.0", 0) — that was the original bug that killed the
        // internet. Instead, the caller adds only the DNS server IPs as routes,
        // so just DNS traffic enters the TUN.
        private const val VPN_ADDRESS = "10.0.0.2"
        private const val VPN_PREFIX_LENGTH = 32
        private const val VPN_MTU = 1500

        // DNS forwarding constants
        private const val DNS_PORT = 53
        private const val PROTO_UDP = 17
        private const val UDP_HEADER_LENGTH = 8
        private const val MAX_PACKET_SIZE = 32 * 1024
        private const val DNS_TIMEOUT_MS = 5000
        // Cap on the DNS response payload we will accept from an upstream.
        // 4096 bytes matches the typical EDNS0 receiver buffer size (RFC 6891
        // section 6.2.3). A response larger than this is either malformed or
        // a malicious amplification attempt — drop it.
        private const val MAX_DNS_RESPONSE_SIZE = 4096

        // Common public-DNS resolver IPs to hijack into the TUN. Apps/browsers
        // that hardcode Google (8.8.8.8, 8.8.4.4), Cloudflare (1.1.1.1), Quad9
        // (9.9.9.9), etc. would otherwise bypass our filtered DNS. Routing
        // these /32 host routes into the TUN lets our forwarder rewrite them
        // to the user-selected filtered upstream.
        //
        // VPN-01 fix: expanded the list to cover the common public resolvers
        // used by browsers (Chrome's "secure DNS" defaults, Firefox DoH
        // fallbacks, Edge, Brave, etc.) and major regional resolvers. This
        // is not exhaustive — a determined user can still point an app at an
        // obscure DNS-over-HTTPS endpoint we don't know about — but it covers
        // the resolvers that ~99% of apps hardcode.
        //
        // VPN-16 fix: renamed from DNS_HIJACK_PREFIXES to DNS_HIJACK_HOSTS
        // because every entry is a /32 host route, not a prefix.
        private val DNS_HIJACK_HOSTS: List<Pair<String, Int>> = listOf(
            // Google Public DNS
            "8.8.8.8" to 32,
            "8.8.4.4" to 32,
            // Cloudflare (unfiltered — the filtered 1.1.1.3 is the upstream)
            "1.1.1.1" to 32,
            "1.0.0.1" to 32,
            // Cloudflare malware-only (used by some browsers as a fallback)
            "1.1.1.2" to 32,
            "1.0.0.2" to 32,
            // Quad9
            "9.9.9.9" to 32,
            "9.9.9.10" to 32,
            "9.9.9.11" to 32,
            "149.112.112.112" to 32,
            // OpenDNS (unfiltered — the filtered 208.67.222.123 is the upstream)
            "208.67.222.222" to 32,
            "208.67.220.220" to 32,
            // AdGuard (unfiltered — the filtered 94.140.14.15 is the upstream)
            "94.140.14.14" to 32,
            "94.140.15.15" to 32,
            // CleanBrowsing (unfiltered — the filtered 185.228.168.168 is the upstream)
            "185.228.168.9" to 32,
            "185.228.169.9" to 32,
            // Comodo Secure DNS
            "8.26.56.26" to 32,
            "8.20.247.20" to 32,
            // Verisign Public DNS
            "64.6.64.6" to 32,
            "64.6.65.6" to 32,
            // Yandex.DNS (common in RU/CIS — base, unfiltered)
            "77.88.8.8" to 32,
            "77.88.8.1" to 32,
            // DNS.WATCH (common in EU)
            "84.200.69.80" to 32,
            "84.200.70.40" to 32,
            // Level3 / CenturyLink (common US carrier DNS)
            "4.2.2.1" to 32,
            "4.2.2.2" to 32,
            "4.2.2.3" to 32,
            "4.2.2.4" to 32,
            "4.2.2.5" to 32,
            "4.2.2.6" to 32
        )

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
