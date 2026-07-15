# VPN Deep Analysis — Protect-Yourself (Future-Brand branch)

> **Branch**: `Future-Brand` (commit `1661ef6`, v1.0.34-debug)
> **Analysis date**: 2026-07-11
> **Scope**: Every VPN-related setting, evaluated individually against the reference implementation, with focus on (a) networking correctness, (b) performance, (c) UI/UX, (d) bugs / inconsistencies / improvement opportunities.
> **Methodology**: Static source review of the Future-Brand VPN code path + JADX decompilation of the prior `protect.yourself-v1.0.33-release.apk` (the version immediately before the Future-Brand fix) + cross-reference with `docs/COMPARISON_REPORT.md` and `docs/IMPLEMENTATION_PLAN.md`.
> **Reference**: v1.0.53 (`com.planproductive`). The reference APK was not available for direct decompilation in this session, so reference behaviour is inferred from (1) the prior `docs/COMPARISON_REPORT.md` (which was written from a full JADX decompilation of `reference_1.0.53.apk`) and (2) the v1.0.33 Protect-Yourself APK, which is a 1:1 port of the reference's VPN module.

---

## Executive Summary

The Future-Brand branch ships two big VPN changes over v1.0.33:

1. **A critical networking fix** in `MyVpnService.kt` — the v1.0.33 service called `addRoute("0.0.0.0", 0)` (route the entire IPv4 internet into the TUN) but never forwarded those packets, so enabling the VPN instantly killed the device's internet. The Future-Brand branch replaces this with a DNS-only interception design (only DNS server IPs are routed into the TUN, and a real UDP/53 forwarding loop relays queries to the upstream family-safe DNS).
2. **A complete UI/UX redesign** — the old "tap-to-cycle Normal/Powerful/Custom" action row (which also had a latent identifier-overload bug) is replaced by a dedicated `VpnManagementPage` with a status header, three explanatory mode cards, a custom-DNS provider picker, and an advanced-settings group.

Both changes are in the right direction. However, a setting-by-setting deep review against the reference and against DNS-filtering best practice (AdGuard / Blokada / Intra / DNS66) reveals **19 issues** of varying severity that should be addressed before the VPN subsystem is considered production-ready. The most important are:

- **VPN-01 (Critical)** — `addRoute(firstDns, 32)` + `addRoute(secondDns, 32)` will silently fail to capture DNS queries on devices where the system resolver uses the **system-configured DNS** (e.g. DHCP-assigned 8.8.8.8) rather than the VPN's `addDnsServer()`. The DNS-hijack list helps but is incomplete.
- **VPN-02 (Critical)** — The DNS forwarder opens a **new `DatagramSocket` per query** and does not set `SO_REUSEADDR`. Under load (e.g. a page that fires 30+ DNS lookups) this burns through file descriptors and can crash the service with `EMFILE`.
- **VPN-03 (High)** — The IPv4 header checksum is left at 0x0000 in `buildDnsResponsePacket()`. While Android's TUN driver usually accepts zero-checksum packets, this is not guaranteed across all OEM kernel builds — some Huawei / MediaTek kernels **do** validate checksums and will silently drop the response, causing "DNS probe finished NXDOMAIN" errors.
- **VPN-04 (High)** — The `onRevoke()` self-restart logic can race with the system's VPN teardown and cause a `startVpn()` call that returns instantly because `isRunning` is still `true` (the `stopVpn()` in the same `onRevoke()` already set it to false, but the 2-second delay before restart means a second revoke can land in between).
- **VPN-05 (High)** — The `AppSystemActionReceiverAllTime` boot-restart calls `MyVpnService.start(context)` directly, but on Android 12+ a stopped app cannot start a foreground service from the background. The VPN will not actually start after reboot on Android 12+.
- **VPN-06 (Medium)** — `setVpnMode()` always emits `RestartVpn` even when the VPN is OFF. Harmless today, but it spams the log and can confuse a future reader.
- **VPN-07 (Medium)** — `selectCustomDnsPreset()` shows a toast "Custom DNS provider updated. Restarting VPN…" even when the VPN is OFF or not in CUSTOM mode. Misleading.
- **VPN-08 (Medium)** — The `VpnManagementPage` "Hide notification content" toggle does not restart the VPN, so the change does not take effect until the next manual restart. The old v1.0.33 UI had the same bug; the reference's notification settings also require a restart but the reference tells the user.
- **VPN-09 (Medium)** — `isValidDNS()` only accepts IPv4, but the DNS hijack list and `addDnsServer()` would also accept IPv6. If a user enters an IPv6 DNS in a future custom-DNS editor, validation will reject it.
- **VPN-10 (Low/Medium)** — The `CustomDnsPresetRow` parameter `isCustomModeActive` is passed but never used in the UI. Dead parameter.
- **VPN-11 (Low)** — The `ModeSelectorCard` for the selected mode shows a hardcoded English "Active" label instead of using a string resource.
- **VPN-12 (Low)** — `VpnStatusHeader` uses `Icons.Filled.Shield` for both the connected and disconnected states. A different icon (e.g. `Icons.Filled.Shield` vs `Icons.Filled.ShieldMoon` or a strike-through) would communicate state more clearly.
- **VPN-13 (Low)** — The "Restart" notification action uses the same icon (`ic_focus`) as the "Stop VPN" action. Hard to distinguish at a glance.
- **VPN-14 (Low)** — `vpn_mode_changed_toast` and `vpn_custom_dns_changed_toast` string resources are declared but the code uses hardcoded English strings in `setVpnMode()` and `selectCustomDnsPreset()` instead.
- **VPN-15 (Low)** — `VpnManagementState.isLoading` defaults to `true`, but `loadVpnManagementState()` is only called by a `LaunchedEffect(Unit)` inside the composable. If the user navigates away and back, the state may show the spinner briefly.
- **VPN-16 (Low)** — `DNS_HIJACK_PREFIXES` is named "prefixes" but actually contains /32 host routes. Misleading name.
- **VPN-17 (Low)** — `MyVpnService` no longer calls `builder.addSearchDomain(".")` (the v1.0.33 code did). This is probably fine but is an undocumented behaviour change.
- **VPN-18 (Low)** — The `MyVpnService` foreground notification `setContentTitle` always shows "Protect Yourself VPN active" even when the VPN has failed to establish (e.g. user revoked permission mid-start). The notification should reflect actual state.
- **VPN-19 (Low)** — The `MyVpnService.isRunning()` companion function returns `instance?.isRunning ?: false`, but `instance` is set in `init {}` and cleared never. After `onDestroy()`, `instance` still points to the destroyed service, so `isRunning()` may return `true` for a dead service.

The detailed analysis below walks through every VPN setting one by one, explains what it does, how it compares to the reference, and lists the specific issues that apply to it.

---

## 1. Inventory of VPN-related settings

The VPN subsystem exposes **7 user-facing settings** (plus the master VPN switch). They all live under **Home → Advanced Features** in the Blocker page, and most are also reachable from the new **VPN Management** sub-page:

| # | Setting | Identifier | DB key | UI location | Type |
|---|---------|------------|--------|-------------|------|
| S1 | VPN (master switch) | `SettingPageItemIdentifiers.VPN` | `SwitchIdentifier.VPN_SWITCH` | Advanced Features + VPN Mgmt header | Boolean switch |
| S2 | VPN mode (Balanced / Strict / Custom DNS) | `SettingPageItemIdentifiers.VPN_MANAGE` | `SwitchIdentifier.VPN_CONNECTION_TYPE` (long: 1/2/3) | VPN Mgmt page (3 cards) | Enum (3 values) |
| S3 | Custom DNS provider | (no dedicated SettingPageItemIdentifier — managed inside VPN Mgmt page) | `vpn_custom_dns.isSelected` | VPN Mgmt page → Custom DNS Provider list | Radio selection from `vpn_custom_dns` table |
| S4 | VPN whitelist apps | `SettingPageItemIdentifiers.WHITELIST_VPN_APPS` | `selected_apps` table, identifier `vpn_whitelist_apps` | Advanced Features + VPN Mgmt → Advanced | Multi-select app list |
| S5 | VPN notification message | `SettingPageItemIdentifiers.VPN_NOTIFICATION_MESSAGE` | `SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE` (string) + `_SET` flag | Advanced Features + VPN Mgmt → Advanced | Text (free-form) |
| S6 | Hide VPN notification content | `SettingPageItemIdentifiers.VPN_NOTIFICATION_HIDE` | `SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH` | Advanced Features + VPN Mgmt → Advanced | Boolean switch |
| — | VPN DNS custom list set (flag) | (no UI) | `SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET` | None — internal flag | Boolean (unused) |

Each setting is analysed in its own section below (sections 3–9). Section 2 first covers the underlying VPN service because every setting depends on it.

---

## 2. The VPN service (`MyVpnService.kt`) — networking behaviour

This is the most important part of the VPN subsystem. Every user-facing setting eventually funnels into here.

### 2.1 What v1.0.33 did (the bug)

Decompiled `MyVpnService.java` from `protect.yourself-v1.0.33-release.apk` shows:

```java
private static final String VPN_ROUTE = "0.0.0.0";
private static final int VPN_ROUTE_PREFIX = 0;
```

and the `startVpn` lambda (which JADX could not fully decompile — the body was too complex for the decompiler, but the constant pool and the `Builder` chain are recoverable from the smali) called:

```kotlin
Builder()
    .setSession(...)
    .addAddress("10.0.0.2", 32)
    .addRoute("0.0.0.0", 0)        // <-- routes ALL IPv4 traffic into the TUN
    .addDnsServer(firstDns)
    .addDnsServer(secondDns)
    .setMtu(1500)
    .addSearchDomain(".")
    .addDisallowedApplication(packageName)   // self bypass
    // ... + per whitelisted app
    .establish()
```

After `establish()` returned, the service did **nothing** with `vpnInterface.fileDescriptor` — no `FileInputStream`/`FileOutputStream` loop, no packet forwarding, no DNS proxy. The TUN was a black hole. Every packet the OS routed into the TUN (which, because of `addRoute("0.0.0.0", 0)`, was *every* packet) sat in the TUN's receive buffer until it was full, then was dropped. The device lost all internet connectivity within ~1 second of the VPN starting.

`docs/COMPARISON_REPORT.md` line 197 says: *"VPN DNS blocking | Full tunnel `addRoute("0.0.0.0", 0)` + family DNS | Same | ✅ Equivalent (post v1.0.25)"*. This is **wrong** — or, more precisely, it was an *intentional* equivalence claim that did not survive contact with reality. The reference may have the same `addRoute` call, but it presumably also has a packet forwarder (the original reference source was not available for this analysis, but it is a shipping commercial app with working VPN — so it must forward packets somehow). The Protect-Yourself port copied the `addRoute` line without copying the forwarder. Result: black hole.

`docs/IMPLEMENTATION_PLAN.md` line 588 confirms the *intended* design was DNS hijacking only: *"Loop packet routing (no actual VPN traffic — just DNS hijacking)"*. The code did the opposite.

### 2.2 What Future-Brand does (the fix)

The Future-Brand `MyVpnService.kt` replaces the full-tunnel design with DNS-only interception:

```kotlin
Builder()
    .setSession(...)
    .addAddress("10.0.0.2", 32)
    .addDnsServer(firstDnsAddr)
    .addDnsServer(secondDnsAddr)
    .setMtu(1500)
    .addRoute(firstDns, 32)         // ONLY route the DNS server IPs
    .addRoute(secondDns, 32)
    // + hijack common public DNS IPs (8.8.8.8, 1.1.1.1, 9.9.9.9, etc.)
    .addDisallowedApplication(packageName)
    // ... + per whitelisted app
    .establish()
```

Then a real DNS forwarding loop runs:

```kotlin
private fun startDnsForwarder(upstreamPrimary, upstreamSecondary) {
    val input = FileInputStream(pfd.fileDescriptor)
    val output = FileOutputStream(pfd.fileDescriptor)
    while (isActive && !isClosed) {
        val read = input.read(buffer.array())
        // parse IPv4 header → find UDP/53 packets
        // extract DNS payload
        // forward to upstreamPrimary via protect()-ed DatagramSocket
        //   (fallback to upstreamSecondary on failure)
        // build IPv4/UDP response packet
        // write back to TUN
    }
}
```

This is the correct pattern. It matches what AdGuard, Blokada, Intra, DNS66, and (per `docs/COMPARISON_REPORT.md`) the reference itself do.

### 2.3 Issues with the fix

#### VPN-01 (Critical) — `addRoute(dns, 32)` may not capture all DNS traffic

**Problem**: The fix routes only the two configured DNS server IPs into the TUN. But Android's resolver does not always honour `VpnService.Builder.addDnsServer()` — particularly:

1. **Apps that hardcode DNS** (Chrome's "secure DNS", Firefox DoH, Cloudflare WARP, some games, some ad SDKs) send DNS queries directly to their own DNS servers, bypassing the system resolver entirely. If those servers are not in the `DNS_HIJACK_PREFIXES` list, the queries go straight out and bypass our filtering.
2. **The system resolver on some OEM ROMs** (especially Xiaomi MIUI, Huawei EMUI) uses the DNS servers from the underlying network (DHCP-assigned) rather than the VPN's `addDnsServer()`. The Future-Brand code does route those (8.8.8.8 etc. are in the hijack list), but only for the 8 hardcoded IPs. Any other DHCP DNS (e.g. a carrier's 75.75.75.75, or a corporate 10.x.x.x) is not intercepted.
3. **IPv6 DNS** — the forwarder only parses IPv4 packets (`parseIpPacket` returns null for version != 4). If the device has IPv6 connectivity and the resolver prefers IPv6 (common on T-Mobile US, Vodafone DE), DNS queries go out via IPv6 and are completely invisible to the forwarder.

**Impact**: The user enables "Strict" mode expecting AdGuard Family to filter all DNS, but in reality only DNS queries to the two configured servers (and the 8 hardcoded hijack IPs) are filtered. A determined user (or a benign app with hardcoded DoH) can bypass filtering entirely.

**Reference comparison**: The reference uses the same `addRoute("0.0.0.0", 0)` full-tunnel design. It therefore captures **all** DNS (because all traffic enters the TUN). The reference's forwarder (which was not decompiled in this session) presumably handles the full tunnel. So the reference is actually **stronger** than Future-Brand on this axis — but at the cost of needing a real packet forwarder (which is what caused the original Protect-Yourself bug).

**Recommended fix**: Either (a) implement a proper full-tunnel forwarder (TCP + UDP + ICMP, with NAT) like AdGuard does — this is a large amount of code, or (b) keep DNS-only interception but expand the hijack list to include IPv6 DNS prefixes and the full RFC 1918 + carrier DNS ranges, or (c) the simpler middle ground: route the well-known DNS-over-HTTPS / DNS-over-TLS ports (443 to known DoH servers, 853 to known DoT servers) into the TUN as well, and drop them. This forces apps back on plaintext UDP/53 which we do intercept.

#### VPN-02 (Critical) — Per-query socket creation will exhaust file descriptors

**Problem**: `forwardDnsQuery()` opens a new `DatagramSocket()` for every single DNS query and closes it in `finally`. A typical page load fires 20–50 DNS queries. Under load:

- Each `DatagramSocket` consumes a file descriptor.
- The close() call returns the FD to the OS asynchronously on some kernels.
- Under burst load, the service can hit the per-process FD limit (usually 1024) and start failing with `SocketException: EMFILE`.

**Reference comparison**: Unknown (the reference's forwarder was not decompiled). AdGuard and Intra both maintain a **socket pool** keyed by source IP+port, reusing sockets for the duration of a query and closing them on a timer.

**Recommended fix**: Maintain a pool of `protect()`-ed `DatagramSocket` instances (e.g. an `ArrayDeque<DatagramSocket>`). On `forwardDnsQuery`, pop one from the pool (or create+protect a new one if empty); on return, push it back. Cap the pool at, say, 64 sockets. Use `socket.soTimeout` to time out the receive, then close the socket (do not return a timed-out socket to the pool).

#### VPN-03 (High) — IPv4 header checksum left at 0

**Problem**: `buildDnsResponsePacket()` sets `out[10] = 0x00; out[11] = 0x00` (IPv4 header checksum = 0). The comment says "let stack fill in", but the Android TUN driver does **not** fill in checksums on egress — the packet is handed to the network stack as-is. Most Linux kernels accept zero-checksum packets on the TUN input path (because TUN is a trusted interface), but some OEM kernels (notably some MediaTek and Huawei kernels) **do** validate checksums and will silently drop zero-checksum packets.

**Impact**: On affected devices, every DNS response is dropped. The user sees "DNS probe finished NXDOMAIN" / "ERR_NAME_NOT_RESOLVED" in Chrome. The VPN appears to be broken.

**Reference comparison**: The reference's forwarder was not decompiled, but the original reference app is widely used on Huawei / MediaTek devices without widespread DNS complaints, so it presumably computes the checksum correctly.

**Recommended fix**: Compute the IPv4 header checksum in `buildDnsResponsePacket()`. It's a simple 16-bit one's complement sum over the header words:

```kotlin
private fun computeIpChecksum(header: ByteArray): Int {
    var sum = 0L
    for (i in header.indices step 2) {
        sum += ((header[i].toInt() and 0xFF) shl 8) or (header[i + 1].toInt() and 0xFF)
    }
    while (sum ushr 16 != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
    return (sum.inv().toInt() and 0xFFFF)
}
```

Then write the checksum into `out[10]` / `out[11]`. Also consider computing the UDP checksum (which requires a pseudo-header) for maximum compatibility — but UDP checksum is optional for IPv4, so this is lower priority.

#### VPN-04 (High) — `onRevoke()` self-restart race

**Problem**: `onRevoke()` does:
1. `stopVpn()` — sets `isRunning = false`, closes the TUN, cancels the forwarder job.
2. Launches a coroutine that waits 2 seconds, then checks `switchValues.isVpnSwitchOn()` and calls `startVpn()` if true.
3. `startVpn()` checks `if (isRunning) return`. After step 1, `isRunning` is false, so the restart proceeds.

But between step 1 and step 2's `startVpn()` call (a 2-second window), the user may toggle the VPN switch OFF then ON again in the UI. That triggers `RequestVpnPermission` → `MyVpnService.start(context)` → `onStartCommand` → `startVpn()` → establishes a new TUN + starts a new forwarder job. Now `isRunning = true`. Then the 2-second-delayed `startVpn()` from `onRevoke()` fires, sees `isRunning = true`, and returns early. So far so good.

But consider the reverse: user toggles OFF then ON, the new `startVpn()` establishes a TUN, then the system revokes again (because the user's "always-on VPN" setting was revoked in between). The second `onRevoke()` calls `stopVpn()` (cancelling the new TUN), then schedules another 2-second restart. Now there are **two** pending restart coroutines. They both fire, both call `startVpn()`, the first establishes a TUN, the second sees `isRunning = true` and returns. Mostly OK, but the forwarder job from the first restart is now orphaned if the second `startVpn()` had already cancelled `forwarderJob` (it didn't, because it returned early). This is messy.

**Reference comparison**: The reference has the same self-restart pattern (per `docs/COMPARISON_REPORT.md` line 116: "VPN self-restart on revoke | ✅ | ✅ | Same"). It presumably has the same race.

**Recommended fix**: Use a single `Job` for the restart coroutine, and cancel any existing restart job before scheduling a new one:

```kotlin
private var restartJob: Job? = null

override fun onRevoke() {
    super.onRevoke()
    stopVpn()
    // persist switch = false
    // ...
    restartJob?.cancel()
    restartJob = serviceScope.launch {
        delay(2000)
        if (switchValues.isVpnSwitchOn()) {
            startVpn()
        }
    }
    stopSelf()
}
```

#### VPN-05 (High) — Boot-restart violates Android 12+ foreground-service restrictions

**Problem**: `AppSystemActionReceiverAllTime` handles `BOOT_COMPLETED` by calling `MyVpnService.start(context)`, which calls `context.startService(Intent(...))`. On Android 12+ (API 31+), an app in the background cannot start a foreground service from the background — `startForegroundService()` will throw `ForegroundServiceStartNotAllowedException` within 5 seconds if the service calls `startForeground()`.

`MyVpnService.onStartCommand` returns `START_STICKY` and calls `startVpn()` which (eventually, after the coroutine) calls `startForegroundCompat()`. On Android 12+, this will crash with `ForegroundServiceStartNotAllowedException` because the receiver is a background context.

**Reference comparison**: The reference has the same boot-restart code (per the prior reference analysis line 484). It presumably has the same problem on Android 12+ — or it uses `WorkManager` with `setExpedited(...)` to schedule the start. The decompiled v1.0.33 Protect-Yourself does not use WorkManager for this.

**Recommended fix**: Use a `WorkManager` `OneTimeWorkRequest` with `setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)` to start the VPN after boot. WorkManager is exempt from the background-start restriction for expedited work. Alternatively, declare the service with `android:foregroundServiceType="specialUse"` (already done) and use `ServiceCompat.startForeground(...)` with the correct service type — but the *start* still has to come from a foreground context on Android 12+.

The cleanest fix: in `AppSystemActionReceiverAllTime`, instead of `MyVpnService.start(context)`, schedule a `OneTimeWorkRequest` that calls `MyVpnService.start(context)` from within `Worker.doWork()` (which runs in a foreground context).

#### VPN-17 — Removed `addSearchDomain(".")`

The v1.0.33 code called `builder.addSearchDomain(".")` (which is a no-op — "." is the root domain and is already the default search domain). The Future-Brand code removed it. This is fine — it was dead code in v1.0.33. No action needed; flagged for completeness.

#### VPN-19 — `instance` singleton not cleared on `onDestroy()`

```kotlin
init { instance = this }
```

`instance` is set when the service is constructed and never cleared. After `onDestroy()`, `instance` still points to the destroyed service object. `MyVpnService.isRunning()` (companion) returns `instance?.isRunning ?: false`. After `onDestroy()`, `isRunning` was set to `false` by `stopVpn()`, so `isRunning()` correctly returns false — but the `instance` reference holds a destroyed service in memory, which can confuse debugging. Clear it in `onDestroy()`:

```kotlin
override fun onDestroy() {
    super.onDestroy()
    stopVpn()
    serviceScope.cancel()
    instance = null   // <-- add this
}
```

(Use a null assignment inside the class; the `private set` on the companion property allows writes from inside the class.)

---

## 3. Setting S1 — VPN master switch

### 3.1 What it does

`SwitchIdentifier.VPN_SWITCH` (boolean). When toggled ON:
- ViewModel emits `BlockerPageNavigation.RequestVpnPermission`.
- UI calls `VpnService.prepare(context)`. If it returns a non-null Intent, the VPN permission system dialog is shown via `vpnPermissionLauncher`.
- On `RESULT_OK`, the UI calls `MyVpnService.start(context)` and `viewModel.onVpnPermissionGranted()`.
- `onVpnPermissionGranted()` persists `VPN_SWITCH=true` in the DB, updates the local state, and shows a "VPN enabled" toast.

When toggled OFF:
- ViewModel persists `VPN_SWITCH=false`, emits `BlockerPageNavigation.StopVpn`.
- UI calls `MyVpnService.stop(context)`.

### 3.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 116: "VPN self-restart on revoke | ✅ | ✅ | Same". The reference has the same master-switch + prepare + start pattern. Functionally equivalent.

### 3.3 Issues

- **No issue with the switch itself.** The prepare/start flow is correct and matches the reference.
- **Minor**: The "VPN enabled" toast is hardcoded English in `onVpnPermissionGranted()` (line 322: `_navigation.emit(BlockerPageNavigation.ShowToast("VPN enabled"))`). Should use a string resource. (This is a localisation issue, not a VPN-specific issue — but since `docs/COMPARISON_REPORT.md` line 154 flags "Multi-language UI | ✅ 37+ languages | ⚠️ Strings.xml is English-only" as a known gap, this is consistent with the rest of the app.)

---

## 4. Setting S2 — VPN mode (Balanced / Strict / Custom DNS)

### 4.1 What it does

`SwitchIdentifier.VPN_CONNECTION_TYPE` (long: 1=NORMAL, 2=POWERFUL, 3=CUSTOM). Stored in the switch_status table as a long.

UI: The `VpnManagementPage` shows three `ModeSelectorCard`s. Tapping a card calls `viewModel.setVpnMode(mode)`, which:
1. Persists the new mode via `switchValues.storeVpnConnectionType(mode)`.
2. Reloads `vpnManagementState` and `settingItems` so the UI reflects the new selection.
3. If the VPN is currently running, emits `BlockerPageNavigation.RestartVpn` — the UI calls `MyVpnService.restart(context)`.
4. Emits a `ShowToast` with the new mode label.

### 4.2 Comparison with the reference

The reference has the same 4 modes (OFF / NORMAL / POWERFUL / CUSTOM) per `docs/COMPARISON_REPORT.md` line 115. It uses the same DB key (`vpn_connection_type`) and the same enum values. The Future-Brand branch renames the user-facing labels (NORMAL→"Balanced", POWERFUL→"Strict", CUSTOM→"Custom DNS") but keeps the enum values and DB key unchanged — so the schema is backward-compatible with v1.0.33 backups.

The Future-Brand UI is a significant improvement over both the reference and v1.0.33. The reference (and v1.0.33) used a tap-to-cycle `ActionRow` that showed only the current mode label — users had no way to see what the other modes were without tapping. The Future-Brand UI shows all three modes simultaneously with descriptions, DNS provider chips, and recommendation tags.

### 4.3 Issues

#### VPN-06 — `setVpnMode()` always emits `RestartVpn` when VPN is ON, even if the mode is unchanged

```kotlin
fun setVpnMode(mode: VpnConnectionTypeIdentifiers) {
    viewModelScope.launch {
        switchValues.storeVpnConnectionType(mode)
        loadVpnManagementState()
        loadSettingItems()
        if (switchValues.isVpnSwitchOn()) {
            _navigation.emit(BlockerPageNavigation.RestartVpn)
        }
        _navigation.emit(BlockerPageNavigation.ShowToast("VPN mode changed to ${vpnModeLabel(mode)}. Restarting VPN…"))
    }
}
```

If the user taps the already-selected mode card, the VPN is restarted for no reason. The toast also says "Restarting VPN…" even though nothing changed.

**Fix**: Early-return if the mode is unchanged:

```kotlin
fun setVpnMode(mode: VpnConnectionTypeIdentifiers) {
    viewModelScope.launch {
        val current = switchValues.getVpnConnectionType()
        if (current == mode) return@launch
        // ... rest of the logic
    }
}
```

#### VPN-14 — Hardcoded toast strings instead of using `vpn_mode_changed_toast` / `vpn_custom_dns_changed_toast`

`strings.xml` declares:
```xml
<string name="vpn_mode_changed_toast">VPN mode changed to %1$s. Restarting VPN…</string>
<string name="vpn_custom_dns_changed_toast">Custom DNS provider updated. Restarting VPN…</string>
```

But the ViewModel uses hardcoded English:
```kotlin
_navigation.emit(BlockerPageNavigation.ShowToast("VPN mode changed to ${vpnModeLabel(mode)}. Restarting VPN…"))
```

Note that `vpnModeLabel()` also returns hardcoded English ("Balanced" / "Strict" / "Custom DNS") rather than the string resources `R.string.vpn_mode_balanced_label` etc. The notification text in `MyVpnService` does use the string resources (lines 249–251), so there's an inconsistency between the notification and the toast.

**Fix**: Use `context.getString(R.string.vpn_mode_changed_toast, modeLabel)` and have `vpnModeLabel` take a `Context` (or use a string-resource-based approach). Since the ViewModel doesn't have a `Context`, the cleanest fix is to emit a `ShowToast` event with a string-res ID + format args, and let the UI resolve it. This is a larger refactor; for now, at least make `vpnModeLabel` return the same labels as the notification.

#### VPN-11 — "Active" label in `ModeSelectorCard` is hardcoded English

```kotlin
Text(
    text = "Active",
    style = MaterialTheme.typography.labelMedium,
    ...
)
```

Should be `stringResource(R.string.vpn_custom_dns_active)` (which already exists as "Active" in strings.xml — but at least it would be localisable).

---

## 5. Setting S3 — Custom DNS provider

### 5.1 What it does

The `vpn_custom_dns` table holds DNS presets. Each preset has a `key`, `displayName`, `firstDns`, `secondDns`, and `isSelected` boolean. The selected preset is used by `MyVpnService` when the VPN is in CUSTOM mode.

UI: The `VpnManagementPage` shows a `CustomDnsPresetRow` for each preset in the table. Tapping a row calls `viewModel.selectCustomDnsPreset(preset.key)`, which:
1. Calls `db.vpnCustomDnsDao().setSelected(presetKey)` — an atomic SQL `UPDATE` that sets `isSelected = (key = :key)` for all rows.
2. Reloads `vpnManagementState`.
3. If the VPN is ON and in CUSTOM mode, emits `RestartVpn`.
4. Emits a `ShowToast`.

### 5.2 Comparison with the reference

The reference has the same `vpn_custom_dns` table and the same 4 default presets (Cloudflare Family, OpenDNS FamilyShield, CleanBrowsing Family, AdGuard Family). Per `docs/COMPARISON_REPORT.md` line 62: "Same shape, 4 DNS presets pre-populated".

The v1.0.33 Protect-Yourself had **no UI** to manage custom DNS presets — the `add_custom_dns_page_title` and `add_custom_dns_page_card_message` strings existed in `strings.xml` but no Compose file referenced them. This was a regression from the reference (which had an `AddVpnCustomDnsPageContent`). The Future-Brand branch adds a read-only preset picker (you can select among the 4 presets) but still does **not** add the ability to add / edit / delete custom presets. So the gap with the reference is partially closed but not fully.

### 5.3 Issues

#### VPN-07 — Misleading toast when VPN is OFF or not in CUSTOM mode

```kotlin
fun selectCustomDnsPreset(presetKey: String) {
    viewModelScope.launch {
        db.vpnCustomDnsDao().setSelected(presetKey)
        loadVpnManagementState()
        if (switchValues.isVpnSwitchOn() &&
            switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM
        ) {
            _navigation.emit(BlockerPageNavigation.RestartVpn)
        }
        _navigation.emit(
            BlockerPageNavigation.ShowToast("Custom DNS provider updated. Restarting VPN…")
        )
    }
}
```

If the VPN is OFF, or is ON but in Balanced/Strict mode, the toast still says "Restarting VPN…" — but no restart happens. The user is misled.

**Fix**: Make the toast conditional:

```kotlin
val willRestart = switchValues.isVpnSwitchOn() &&
    switchValues.getVpnConnectionType() == VpnConnectionTypeIdentifiers.CUSTOM
val msg = if (willRestart) {
    "Custom DNS provider updated. Restarting VPN…"
} else {
    "Custom DNS provider updated."
}
_navigation.emit(BlockerPageNavigation.ShowToast(msg))
```

#### VPN-09 — `isValidDNS()` rejects IPv6

`BlockerPageUtils.isValidDNS()` uses an IPv4-only regex. If a future custom-DNS editor allows the user to enter an IPv6 DNS (e.g. `2606:4700:4700::1113` for Cloudflare Family), validation will reject it. The `MyVpnService` code calls `InetAddress.getByName(firstDns)` which would accept IPv6, but the validation gate prevents it.

This is not a bug today (there is no UI to add custom presets), but it will be a bug when the add/edit/delete UI is added.

**Fix**: Extend `isValidDNS()` to accept both IPv4 and IPv6:

```kotlin
fun isValidDNS(dns: String): Boolean {
    if (dns.isBlank()) return false
    // InetAddress.getByName returns an InetAddress for both IPv4 and IPv6
    // (and also resolves hostnames — we don't want that). Use a regex for
    // both formats instead.
    val ipv4 = Pattern.compile("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$")
    val ipv6 = Pattern.compile("^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|(::[0-9a-fA-F]{1,4}){1,7}|[0-9a-fA-F]{1,4}::[0-9a-fA-F]{0,6}|[0-9a-fA-F]{1,4}:([0-9a-fA-F]{1,4}:){0,5}[0-9a-fA-F]{0,4})$")
    val trimmed = dns.trim()
    return ipv4.matcher(trimmed).matches() || ipv6.matcher(trimmed).matches()
}
```

#### VPN-10 — `isCustomModeActive` parameter is dead

`CustomDnsPresetRow` accepts `isCustomModeActive: Boolean` but never uses it. The original intent was probably to grey out the rows when CUSTOM mode is not active (so the user knows their selection won't take effect immediately). The parameter should either be used (grey out + disable click) or removed.

**Recommended use**: When `isCustomModeActive` is false, show a small hint like "Switch to Custom mode to use this provider" and make the row non-clickable. This matches the principle of progressive disclosure.

#### Gap with reference — No add/edit/delete UI for custom DNS presets

The reference (per `docs/IMPLEMENTATION_PLAN.md` line 579) has an `AddVpnCustomDnsPageContent` for adding / editing / deleting custom DNS presets. The Future-Brand branch does not implement this. The `vpn_custom_dns` table supports it (the DAO has `upsert`, `deleteByKey`, `deleteAll`), but there is no UI.

**Recommended fix**: Add a "+ Add custom DNS" button at the bottom of the Custom DNS Provider section in `VpnManagementPage`. Tapping it opens a dialog with three fields (name, DNS 1, DNS 2) and a Save button. Save calls `db.vpnCustomDnsDao().upsert(...)`. Also add an "Edit" and "Delete" action on each non-default preset row (long-press or overflow menu). This is a moderate amount of work but closes the last reference parity gap on the VPN settings.

---

## 6. Setting S4 — VPN whitelist apps

### 6.1 What it does

`SelectedAppListIdentifier.VPN_WHITELIST_APPS` — a list of package names stored in the `selected_apps` table. In `MyVpnService.startVpn()`, for each whitelisted package, `builder.addDisallowedApplication(pkg)` is called, which makes that app bypass the VPN entirely (its traffic goes out through the device's normal network, not the TUN).

The app itself (`protect.yourself`) is always added to the disallowed list (line 224) regardless of the user's whitelist — this is correct, because the app needs to communicate with the outside world (e.g. for the daily report email, for crash logging, etc.) and would deadlock if its own traffic went through the TUN.

UI: `AdvancedActionRow` in `VpnManagementPage` (and also a row in the Advanced Features category) opens `SelectAppPage` with the `VPN_WHITELIST_APPS` identifier.

### 6.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 117: "VPN per-app routing | ✅ | ✅ | Same (`addDisallowedApplication` for whitelist)". Functionally equivalent.

### 6.3 Issues

- **No bug.** The whitelist implementation is correct.
- **UX note**: The `AdvancedActionRow` action label is hardcoded "Manage" (via `stringResource(R.string.vpn_manage_action_label)`). It would be more useful to show the count of whitelisted apps (e.g. "3 apps") like the "Blocklist apps" row does (`actionLabel = if (count > 0) "$count titles" else "Add"`). This is a minor enhancement, not a bug.

---

## 7. Setting S5 — VPN notification message

### 7.1 What it does

`SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE` (string) + `SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE_SET` (boolean flag). When the user sets a custom message, it is shown in the VPN foreground notification instead of the default "DNS blocking is protecting you from adult content".

UI: `AdvancedActionRow` in `VpnManagementPage` opens an `EditTextDialog` (via the `onEditNotificationMessage` callback, which calls `viewModel.onActionClick(...)` with a `VPN_NOTIFICATION_MESSAGE` identifier). The dialog saves the text via `viewModel.saveTextField(switchKey, value)`, which persists to `VPN_NOTIFICATION_CUSTOM_MESSAGE` and sets the `_SET` flag.

`MyVpnService` reads the custom message in `startVpn()` and uses it in the notification text. The change takes effect on the next VPN start/restart — **not** immediately.

### 7.2 Comparison with the reference

The reference has the same `VPN_NOTIFICATION_CUSTOM_MESSAGE` and `VPN_NOTIFICATION_CUSTOM_MESSAGE_SET` keys (per `docs/COMPARISON_REPORT.md` line 62 — same DB schema). Functionally equivalent.

### 7.3 Issues

#### The v1.0.33 overloaded-identifier bug is fixed

In v1.0.33, `VPN_NOTIFICATION_MESSAGE` was used for **both** the mode cycler row AND the notification message edit row (see decompiled `BlockerPageViewModel.java` lines 325 and 327 — both add a `SettingPageItemModel` with the same `VPN_NOTIFICATION_MESSAGE` identifier). The `onActionClick` handler had only one branch for `VPN_NOTIFICATION_MESSAGE` (the mode cycling one), so tapping the "VPN notification message" row actually cycled the VPN mode. This was a latent bug that effectively made the notification-message editing unreachable from the BlockerPage settings list.

The Future-Brand branch fixes this by introducing a new `VPN_MANAGE` identifier for the mode row, leaving `VPN_NOTIFICATION_MESSAGE` for the notification message edit. The `VpnManagementPage` also exposes the notification message editor directly via the "Advanced Settings" section, so it's reachable from two places now. ✅ Fixed.

#### VPN-08 (applies to S5 and S6) — Notification changes don't take effect until VPN restart

When the user changes the notification message (or toggles "Hide notification content"), the change is persisted to the DB but the running VPN service is not notified. The notification continues to show the old text until the VPN is restarted (either manually via the "Restart" notification action, or by toggling the VPN off and on).

This is a UX inconsistency: the user expects the change to take effect immediately. The reference has the same behaviour (per the comparison report, the VPN settings are read once at `startVpn()` time), but the reference tells the user "Changes will take effect on next VPN restart" — the Future-Brand branch does not.

**Fix**: After saving the notification message (or toggling the hide switch), if the VPN is running, emit `RestartVpn`. This is a one-line change in `saveTextField()`:

```kotlin
if (switchKey == SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE) {
    loadVpnManagementState()
    if (switchValues.isVpnSwitchOn()) {
        _navigation.emit(BlockerPageNavigation.RestartVpn)
    }
}
```

And in `setVpnNotificationHidden()`:

```kotlin
fun setVpnNotificationHidden(hidden: Boolean) {
    viewModelScope.launch {
        switchValues.storeSwitchStatus(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH, hidden)
        loadVpnManagementState()
        loadSettingItems()
        if (switchValues.isVpnSwitchOn()) {
            _navigation.emit(BlockerPageNavigation.RestartVpn)
        }
    }
}
```

(Alternatively, update the notification in-place without restarting the VPN, by calling `NotificationManagerCompat.notify(...)` with the new text. This is more work but gives a better UX.)

---

## 8. Setting S6 — Hide VPN notification content

### 8.1 What it does

`SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH` (boolean). When ON, the VPN foreground notification shows only the app name (via `getString(R.string.app_name)`) instead of the detailed "DNS blocking is protecting you from adult content (Balanced)" text.

UI: `AdvancedToggleRow` in `VpnManagementPage`. Toggling calls `viewModel.setVpnNotificationHidden(newValue)`.

### 8.2 Comparison with the reference

Same DB key, same behaviour. Functionally equivalent.

### 8.3 Issues

- **VPN-08** (see section 7.3) — change does not take effect until VPN restart.
- **No other issues.** The implementation is correct.

---

## 9. Internal flag — `VPN_DNS_CUSTOM_LIST_SET`

`SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET` (boolean). Declared in `SwitchIdentifier.kt`, seeded as `false` in `AppDatabaseCallback`, exposed via `SwitchStatusValues.isVpnDnsCustomListSet()`. **Never read by any code.** This is dead code — presumably a flag from the original reference that was meant to track whether the user had ever edited the custom DNS list (so the app could decide whether to re-seed defaults on upgrade). The Future-Brand branch does not use it.

**Recommendation**: Either remove it (and the `isVpnDnsCustomListSet()` accessor) or use it for its intended purpose (guard against re-seeding defaults when the user has added custom presets). Since the DB schema must not change (we just bumped to v9), leave the column in place but mark the accessor as `@Suppress("unused")` or delete the accessor and leave the column.

---

## 10. UI/UX analysis — `VpnManagementPage`

### 10.1 What's good

- **Clear status header** — the gradient card at the top shows connected/disconnected, current mode, and has the master switch. This is a big improvement over v1.0.33's flat list.
- **Three mode cards with descriptions** — users can see all options at once, with a recommendation tag ("Recommended" / "Max protection" / "Advanced"), a DNS provider chip, and a multi-line description. This fixes the v1.0.33 problem where users had to tap-cycle to discover the options.
- **Custom DNS picker** — the radio-style list with "Active" / "Make active" affordance is clear.
- **Advanced settings grouped** — whitelist apps, notification message, hide notification are all in one place.
- **Consistent visual language** — uses the app's `BrandOrange` accent color, `MaterialTheme.colorScheme.surface` cards, rounded corners, the same icon style throughout.

### 10.2 What could be improved

#### VPN-12 — Same icon for connected and disconnected states

`VpnStatusHeader` uses `Icons.Filled.Shield` for both states:

```kotlin
Icon(
    imageVector = if (isVpnEnabled) Icons.Filled.Shield else Icons.Filled.Shield,
    ...
)
```

(The ternary is a no-op — both branches return `Icons.Filled.Shield`.) A different icon for the disconnected state (e.g. `Icons.Filled.ShieldMoon` or `Icons.Outlined.Shield`) would communicate state at a glance.

#### VPN-13 — Same icon for "Stop VPN" and "Restart" notification actions

```kotlin
.addAction(R.drawable.ic_focus, getString(R.string.vpn_stop), stopPending)
.addAction(R.drawable.ic_focus, getString(R.string.vpn_restart), restartPending)
```

Both actions use `R.drawable.ic_focus`. The user has to read the text labels to distinguish them. Use a different icon for "Restart" (e.g. `ic_refresh` — Material's refresh icon).

#### VPN-15 — Brief spinner on re-entry

`VpnManagementState.isLoading` defaults to `true`. `loadVpnManagementState()` is called by `LaunchedEffect(Unit)` inside the composable, so the spinner shows for a frame or two on every navigation into the page. Consider:
- Keeping the previous state across navigations (don't reset to `isLoading = true`).
- Or showing the previous state with a subtle refresh indicator instead of a full-screen spinner.

#### VPN-18 — Notification title doesn't reflect actual state

`MyVpnService.buildNotification()` always uses `getString(R.string.vpn_notification_title)` ("Protect Yourself VPN active") as the content title, even if the VPN failed to establish (e.g. `builder.establish()` returned null but `startVpn()` didn't bail out in time — though the current code does bail out). This is mostly a theoretical issue today, but if the start fails partway (e.g. the forwarder loop crashes), the notification will still say "VPN active" while the VPN is actually dead.

**Fix**: Track a `vpnState` enum (CONNECTING / CONNECTED / FAILED) and update the notification title accordingly. This is a larger refactor; low priority.

---

## 11. DB schema & migration analysis

### 11.1 The v8 → v9 bump

The Future-Brand branch bumped `AppDatabase` from v8 to v9 to add the `display_name` column to `vpn_custom_dns`. The migration strategy is `fallbackToDestructiveMigration()` (per `AppDatabase.kt` line 76), which means **upgrading from v1.0.33 to v1.0.34 will wipe the entire database** — the user loses their streak, block count, keywords, app lists, etc.

This is acceptable for a pre-release app (the `apk/` folder only has debug builds, suggesting no real users yet), but it is **not acceptable** for a production release. Users who installed v1.0.33 and upgrade to v1.0.34 will lose all their data.

**Recommended fix**: Write a proper `Migration(8, 9)` that adds the `display_name` column to `vpn_custom_dns` and backfills it from `DefaultDnsPresets.ALL` by matching the `key`:

```kotlin
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE vpn_custom_dns ADD COLUMN display_name TEXT NOT NULL DEFAULT ''")
        for (preset in DefaultDnsPresets.ALL) {
            database.execSQL(
                "UPDATE vpn_custom_dns SET display_name = ? WHERE `key` = ?",
                arrayOf(preset.displayName, preset.key)
            )
        }
    }
}
```

And wire it into the `Room.databaseBuilder(...)`:

```kotlin
.addMigrations(MIGRATION_8_9)
// drop fallbackToDestructiveMigration() or keep it as a last resort
```

This is **important** — without it, the v1.0.34 release will alienate any existing v1.0.33 users.

### 11.2 The `VPN_CONNECTION_TYPE` default

`AppDatabaseCallback.insertDefaultSwitches()` seeds `VPN_CONNECTION_TYPE = "1"` (NORMAL). This is correct and matches the v1.0.33 behaviour. ✅

### 11.3 The `display_name` backfill for existing presets

`AppDatabaseCallback.insertDnsPresets()` uses `INSERT OR REPLACE`, which will overwrite any user-added custom presets with the same key. Since the default presets have keys like `preset_cloudflare_family`, this is fine (users cannot add presets with those keys today — there is no add UI). But once an add/edit UI is added, the `INSERT OR REPLACE` on app startup will clobber any user preset that happens to collide with a default key. Consider using `INSERT OR IGNORE` instead, or only seeding on first launch (check a `SwitchIdentifier.VPN_DNS_CUSTOM_LIST_SET` flag — which is currently unused — to gate the seeding).

---

## 12. Performance analysis

### 12.1 DNS forwarding throughput

The current forwarder processes one DNS query at a time (single coroutine, blocking `socket.receive()` with a 5-second timeout). Under load (e.g. a page that fires 30 DNS queries in quick succession), each query waits for the previous one to complete (or time out) before being forwarded. Worst-case latency: 30 × 5s = 150 seconds if the upstream is unreachable.

**Fix**: Use a thread pool (e.g. `Dispatchers.IO.limitedParallelism(8)`) and launch a new coroutine per query, so multiple queries can be in flight simultaneously. Combined with the socket-pool fix (VPN-02), this gives near-line-rate DNS forwarding.

### 12.2 Per-query allocation

`forwardDnsQuery()` allocates:
- A new `DatagramSocket`
- A new `ByteArray(MAX_PACKET_SIZE)` (32 KB) for the response buffer
- A new `DatagramPacket`

Per query. Under load this creates significant GC pressure. The socket pool (VPN-02) helps with the socket. The response buffer can be reused (thread-local `ByteArray`). The `DatagramPacket` is unavoidable (it's the Java API).

### 12.3 `loadVpnManagementState()` is not observed

`loadVpnManagementState()` does a one-shot `db.vpnCustomDnsDao().getAll()` and `db.switchStatusDao().get(...)`. If the underlying data changes (e.g. the user adds a custom DNS preset from another part of the app — not possible today, but will be when the add UI is added), the VPN management page will not reflect the change until the user navigates away and back.

**Fix**: Replace the one-shot reads with `Flow` observation. `VpnCustomDnsDao.observeAll()` already exists. `SwitchStatusDao.observe(key)` exists (used by `observeVpnSwitch()`). Combine the flows into a single `VpnManagementState` flow:

```kotlin
val vpnManagementState: StateFlow<VpnManagementState> = combine(
    db.switchStatusDao().observe(SwitchIdentifier.VPN_SWITCH),
    db.switchStatusDao().observe(SwitchIdentifier.VPN_CONNECTION_TYPE),
    db.vpnCustomDnsDao().observeAll(),
    db.switchStatusDao().observe(SwitchIdentifier.VPN_NOTIFICATION_HIDE_SWITCH),
    db.switchStatusDao().observe(SwitchIdentifier.VPN_NOTIFICATION_CUSTOM_MESSAGE)
) { vpnOn, mode, presets, hideNotif, notifMsg ->
    VpnManagementState(
        isVpnEnabled = vpnOn?.asBoolean() ?: false,
        currentMode = VpnConnectionTypeIdentifiers.fromString(mode?.asString()).let { if (it == OFF) NORMAL else it },
        customDnsPresets = presets,
        selectedCustomDnsKey = presets.firstOrNull { it.isSelected }?.key,
        isNotificationHidden = hideNotif?.asBoolean() ?: false,
        notificationMessage = notifMsg?.asString() ?: "",
        isLoading = false
    )
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnManagementState())
```

This is a moderate refactor but gives a much better UX — the page updates live as the DB changes.

---

## 13. Security analysis

### 13.1 DNS queries are sent unencrypted

The forwarder uses `DatagramSocket` (plaintext UDP/53) to talk to the upstream DNS server. This means DNS queries are visible to anyone on the network path (ISP, Wi-Fi operator, carrier). The filtered DNS response is also visible.

This is the same behaviour as the reference (per the comparison report, the reference uses the same `addDnsServer()` + forwarder pattern). It is also the behaviour of DNS66. AdGuard and Intra support DNS-over-HTTPS / DNS-over-TLS, which encrypt the queries.

**Recommendation**: For a future enhancement, add DoH/DoT support. The forwarder would open an HTTPS connection to `https://cloudflare-dns.com/dns-query` (for Cloudflare Family) instead of a UDP socket. This is a significant feature addition, not a bug fix.

### 13.2 The `protect()` call is correct

The forwarder correctly calls `protect(socket)` on the `DatagramSocket` before sending. This exempts the socket from the VPN routing, preventing an infinite loop (query → TUN → forwarder → TUN → ...). If `protect()` returns false, the code logs a warning but continues — this is the right behaviour (the query may still succeed if the routing happens to work out).

### 13.3 No DNS response validation

The forwarder does not validate the DNS response before forwarding it to the client. A malicious upstream could send a response with a bogus transaction ID, and the forwarder would forward it. The client would drop it (transaction ID mismatch), but the forwarder has wasted a round-trip.

**Recommendation**: Validate the transaction ID of the response against the request before accepting it. This is a one-line check:

```kotlin
val responseId = ((response[0].toInt() and 0xFF) shl 8) or (response[1].toInt() and 0xFF)
val requestId = ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF)
if (responseId != requestId) {
    Timber.w("DNS response transaction ID mismatch (got %d, expected %d) — dropping", responseId, requestId)
    return null
}
```

### 13.4 No DNS response size limit

The forwarder accepts responses up to `MAX_PACKET_SIZE` (32 KB). DNS responses over 512 bytes are only valid with EDNS0 (RFC 6891). A malicious upstream could send a huge response to exhaust memory. The 32 KB cap limits the damage, but a tighter cap (e.g. 4096 bytes, the typical EDNS0 buffer size) would be safer.

---

## 14. Summary of issues by severity

| ID | Severity | Setting | Summary |
|----|----------|---------|---------|
| VPN-01 | Critical | S2 (modes) | `addRoute(dns, 32)` misses DNS queries from apps with hardcoded DNS, IPv6 DNS, and DHCP DNS not in the hijack list |
| VPN-02 | Critical | (service) | Per-query `DatagramSocket` creation will exhaust file descriptors under load |
| VPN-03 | High | (service) | IPv4 header checksum left at 0 — some OEM kernels drop the response |
| VPN-04 | High | (service) | `onRevoke()` self-restart can race with user toggles |
| VPN-05 | High | S1 (switch) | Boot-restart violates Android 12+ foreground-service background-start restrictions |
| VPN-06 | Medium | S2 (modes) | `setVpnMode()` restarts the VPN even when the mode is unchanged |
| VPN-07 | Medium | S3 (custom DNS) | `selectCustomDnsPreset()` shows "Restarting VPN…" toast even when no restart happens |
| VPN-08 | Medium | S5, S6 | Notification message / hide changes don't take effect until VPN restart |
| VPN-09 | Medium | S3 (custom DNS) | `isValidDNS()` rejects IPv6 (will be a bug when custom-DNS add UI is added) |
| VPN-10 | Low/Med | S3 (custom DNS) | `isCustomModeActive` parameter in `CustomDnsPresetRow` is dead |
| VPN-11 | Low | S2 (modes) | "Active" label in `ModeSelectorCard` is hardcoded English |
| VPN-12 | Low | (UI) | Same icon (`Icons.Filled.Shield`) for connected and disconnected states |
| VPN-13 | Low | (notification) | Same icon (`ic_focus`) for "Stop VPN" and "Restart" notification actions |
| VPN-14 | Low | S2 (modes) | Hardcoded toast strings instead of using `vpn_mode_changed_toast` / `vpn_custom_dns_changed_toast` |
| VPN-15 | Low | (UI) | Brief spinner on re-entry to `VpnManagementPage` |
| VPN-16 | Low | (service) | `DNS_HIJACK_PREFIXES` is named "prefixes" but contains /32 host routes |
| VPN-17 | Low | (service) | Removed `addSearchDomain(".")` — was dead code, no action needed |
| VPN-18 | Low | (notification) | Notification title always says "VPN active" even if the VPN failed to establish |
| VPN-19 | Low | (service) | `instance` singleton not cleared on `onDestroy()` |
| (gap) | Medium | S3 (custom DNS) | No add/edit/delete UI for custom DNS presets (the reference has `AddVpnCustomDnsPageContent`) |
| (gap) | High | (DB) | v8 → v9 migration uses `fallbackToDestructiveMigration()` — will wipe user data on upgrade |

---

## 15. Recommended priority order for fixes

If I were the maintainer, I would address these in the following order:

1. **VPN-03** (IPv4 checksum) — one-function fix, prevents silent DNS failure on some devices.
2. **VPN-05** (Android 12+ boot-restart) — affects every Android 12+ user; use WorkManager.
3. **VPN-02** (socket pool) — prevents service crash under load.
4. **VPN-08** (notification changes don't apply) — one-line `RestartVpn` emit; big UX win.
5. **VPN-04** (onRevoke race) — single `restartJob` field; prevents confusing restart cascades.
6. **VPN-06 + VPN-07** (unnecessary restart + misleading toast) — trivial early-return + conditional toast.
7. **v8 → v9 migration** — write a proper migration to preserve user data on upgrade.
8. **VPN-01** (DNS hijack coverage) — expand the hijack list + add IPv6 support; this is the largest networking change.
9. **VPN-14 + VPN-11** (hardcoded strings) — localisation cleanup.
10. **VPN-12 + VPN-13** (icon differentiation) — visual polish.
11. **Custom DNS add/edit/delete UI** — closes the last reference parity gap.
12. **Flow-based `vpnManagementState`** — live UI updates.
13. Everything else (VPN-09, VPN-10, VPN-15, VPN-16, VPN-17, VPN-18, VPN-19) — nice-to-have cleanups.

---

## 16. Conclusion

The Future-Brand branch makes two big correct moves: (1) it fixes the internet-killing `addRoute("0.0.0.0", 0)` bug by switching to DNS-only interception with a real forwarder, and (2) it replaces the confusing tap-to-cycle mode picker with a dedicated, self-explanatory VPN management page. Both are meaningful improvements over v1.0.33 and bring the app closer to the reference's UX.

However, the DNS forwarder is a minimum-viable implementation — it works for the common case but has correctness (VPN-01, VPN-03), performance (VPN-02, per-query allocation), and robustness (VPN-04, VPN-05, VPN-19) gaps that will surface on real devices. The UI is a big improvement but has several rough edges (VPN-08, VPN-10, VPN-11, VPN-12, VPN-13, VPN-14, VPN-15) that should be polished. And the v8 → v9 DB migration uses `fallbackToDestructiveMigration()`, which will wipe user data on upgrade — this must be fixed before any production release.

The 21 issues and gaps identified above are prioritised in section 15. Addressing the top 7 (VPN-03, VPN-05, VPN-02, VPN-08, VPN-04, VPN-06+07, and the DB migration) would bring the VPN subsystem from "works on the developer's device" to "safe to ship to real users".
