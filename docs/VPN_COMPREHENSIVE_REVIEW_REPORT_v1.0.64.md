# Comprehensive VPN Review — Findings & Fixes (v1.0.64)

**Scope**: every VPN-related setting and code path — `MyVpnService`
lifecycle, boot/connectivity receivers, restore pipeline, UI toggle and
management flows, custom DNS management, notification handling, per-app
block mode interplay, and backup/restore.
**Method**: full manual code review of all 30+ VPN-touching files,
cross-checked against Android API behavior on all supported versions
(minSdk 26 → targetSdk 35).

**Result**: 5 confirmed issues found and fixed. Everything else reviewed
was either correct or structurally defended already. 5 new regression
tests added (318/318 total pass).

---

## Fixed issues

### VPN-CONN-01 — VPN restart on connectivity change was dead code on Android 12+

**File**: `commons/utils/broadcastReceivers/AppSystemActionReceiver.kt`

The BUG-12 restart path called `MyVpnService.start(context)` **directly from
a broadcast receiver**. On Android 12+ (API 31+) that throws
`ForegroundServiceStartNotAllowedException`, silently swallowed inside the
service starter — the exact same failure class as BOOT-VPN-01 (fixed in
v1.0.63). The path only ever worked on API ≤ 30.

Additionally, the receiver's liveness check used `MyVpnService.isRunning()`,
which is `false` during the CONNECTING window — a network flap while the VPN
was mid-start would issue a redundant start.

**Fix**: the receiver now schedules the expedited `VpnRestartWorker`
(FGS-start-exempt on all API levels, verified start, REPLACE-deduped), and
treats CONNECTING/CONNECTED as running.

### VPN-STOP-02 — stopping an already-dead VPN flashed a "Connecting…" notification

**File**: `features/blockerPage/service/MyVpnService.kt` (`companion.stop()`)

`stop()` unconditionally sent ACTION_STOP via `startForegroundService()`.
When the service wasn't running, this STARTED the service just to stop it:
`onStartCommand` posted the placeholder foreground notification (required by
the BUG-01b 5-second rule), then `stopSelf()` — a confusing flash of the VPN
notification right after the user turned the VPN OFF (e.g. toggle-off while
the service had silently died, double stop from two UIs).

**Fix**: `stop()` early-returns when the service instance doesn't exist and
the state is IDLE/FAILED. Every existing stop caller already persists
`VPN_SWITCH=false` itself (ViewModel toggle, navigation handler, ACTION_STOP
handler, `onRevoke`), so state stays honest; a live service is stopped via
the unchanged intent path.

### VPN-RESTORE-03 — backup/restore never reconciled the live VPN service

**File**: `features/backupRestore/BackupRestoreViewModel.kt`

After a successful import, only the accessibility service was refreshed.
The restored `VPN_SWITCH`, `VPN_CONNECTION_TYPE`, and custom DNS presets
diverged from the live service until the next reboot or VPN page visit:
a backup with VPN=ON restored onto a device with the VPN down left the user
unprotected with the DB claiming ON (worse: VPN consent is per-device and
NOT portable, so ON can never hold on a new device).

**Fix**: post-import `reconcileVpnWithRestoredState()`:
ON+down → start (or sync switch OFF when consent is missing on this device);
OFF+running → stop; ON+running → restart to apply restored mode/DNS;
OFF+down → no-op. Safe on all API levels — the app is foreground during
import.

### VPN-NOTIF-04 — misleading copy in the VPN-permission notification

**Files**: `commons/utils/notificationUtils/NotificationHelper.kt`,
`commons/utils/vpn/VpnRestoreHelper.kt`

`showVpnPermissionRequiredNotification()` had hardcoded scheduled-app-
restriction copy but was also posted from the boot-restore path when VPN
consent was revoked (v1.0.63) — telling the user about "scheduled app
restriction" when their *VPN protection* had silently dropped.

**Fix**: the function takes optional `title`/`text`/`bigText` (defaults =
original schedule copy, so the `ScheduleEngine` call site is unchanged);
the boot-restore path passes revoke-specific copy.

### VPN-STATE-05 — overlapping restore triggers fired redundant starts

**File**: `commons/utils/vpn/VpnRestoreHelper.kt`

`restoreIfEnabled()` treated only post-establish `isRunning()` as "running".
During the CONNECTING window, a second trigger (boot worker + backup alarm +
periodic reconcile can overlap) fired a redundant start intent and sat
through the 8 s verify window.

**Fix**: CONNECTING/CONNECTED are treated as running — one pipeline start
at a time, no redundant intents.

---

## Reviewed and confirmed correct (no change needed)

| Area | Verdict |
|---|---|
| Boot restore pipeline (Worker + backup alarm + reconcile) | Fixed & hardened in v1.0.63; layering is sound — REPLACE dedupes, alarms no-op at fire time, direct-boot guard defers to post-unlock. |
| `startVpn()` guards (`isRunning`, `isStarting` atomic, post-establish staleness check BUG-04) | Solid — concurrent starts collapse; stale establish() results torn down. |
| `ACTION_STOP` persistence (BOOT-VPN-03) | Synchronous `runBlocking` write before `stopSelf()` — user stop survives reboot. |
| Sticky/null-intent restart (BOOT-VPN-02) | Consults `VPN_SWITCH`; FAILSAFE default biased to ON is sound because `startVpn()` re-reads DB itself and fails safe to `stopSelf()`. |
| `onRevoke()` | Synchronous `VPN_SWITCH=false`, per-app-block state cleared, ScheduleEngine cache reset. |
| `onDestroy()` | `stopVpn()` → state IDLE, scope cancelled, instance cleared — no leaks. |
| startForeground plumbing | Placeholder-then-final notification satisfies the API 26+ 5 s rule; `FOREGROUND_SERVICE_TYPE_SPECIAL_USE` + manifest `specialUse` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` correct for API 34/35. |
| Connection-mode switching (`setVpnMode`) | No-op on unchanged mode, restart only when VPN ON, localized toasts. |
| Custom DNS add/delete (`addCustomDnsPreset` / `deleteCustomDnsPreset`) | IPv4+IPv6 validation, dup name/DNS-pair rejection, DNS1==DNS2 rejection, UUID keys, DB-write-before-dismiss, delete-of-selected falls back to Cloudflare + restart. |
| PRESET seeding (`AppDatabaseCallback.insertDnsPresets`) | `INSERT OR IGNORE` with correct camelCase columns; Cloudflare default selected; companion schema-repair covers legacy snake_case. |
| CUSTOM mode with no selection | Falls back to Cloudflare Family (defensive, logged). |
| DNS validation (`isValidDNS`) | Strict IPv4 octet bounds + full/compressed/bracketed IPv6. |
| `loadSwitchValue(VPN_SWITCH)` / `loadVpnManagementState()` sync | `observableVpnState`-based, CONNECTING-aware restart attempt, consent-revoked → DB sync. Consistent with live state. |
| VPN permission UX (`VpnService.prepare` launchers × 3) | Result-gated start + `onVpnPermissionGranted` persist + management-state refresh; cancel path leaves switch OFF. |
| Per-app-block mode vs DNS-filter mode | `allowBypass()` correctly omitted only in per-app-block mode; whitelist vs. allowed-app routing exclusive per mode; self always bypasses in filter mode. |
| `clearScheduledBlockApps()` | Honors `VPN_SWITCH` (restart in filter mode vs. stop), exception path defaults to restart (fail-safe to protection). |
| Establish-failure DB sync | Clears `VPN_SWITCH` only in DNS-filter mode, preserving scheduled-block retry semantics. |
| Notification stop/restart actions | Immutable flagged PendingIntents, distinct request codes, correct channel. |
| Boot receiver VPN delegations | `VpnRestoreHelper.scheduleBootRestore` is switch-gated, unlock-gated, idempotent. |
| Worker cancellation semantics | `CancellationException` rethrown (REPLACE actually stops stale workers). |

## Version & verification (v1.0.64)

- versionCode 62→64 on this branch (63 was the boot-restore fix; 64 is this review).
- `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleRelease`
  → **BUILD SUCCESSFUL**, **318/318 tests pass, 0 failures/errors/skipped**.
- `apksigner verify` → v2 signature valid.
- `aapt2 dump badging` → `protect.yourself`, versionCode `64`, `1.0.64`,
  minSdk 26, targetSdk 35.
- New tests (`VpnReviewFixesTest`): stop-guard (no service start when dead),
  notification copy (default + custom), `restoreIfEnabled` NOT_ENABLED,
  backup-alarm delay invariants. Combined with v1.0.63's enqueue regression
  suite, every fix in both rounds is pinned by an automated test.
