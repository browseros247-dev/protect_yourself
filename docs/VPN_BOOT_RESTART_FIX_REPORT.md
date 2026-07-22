# VPN Auto-Restart After Reboot — Root Cause Analysis & Fix (v1.0.63)

**Issue**: The VPN does not automatically restart after a device reboot even
though it was enabled (VPN switch ON) before the reboot.
**Severity**: Critical — silently defeats the app's core protection promise
("survives reboots") on every Android 12+ device.
**Status**: Fixed in v1.0.63 (branch `fix/vpn-boot-restore-v1.0.63`).

---

## 1. Symptom

1. User enables the VPN (DNS filtering). `VPN_SWITCH=true` is persisted in
   the Room DB and the `MyVpnService` foreground service runs.
2. Device reboots.
3. **Expected**: VPN tunnel re-establishes automatically within a minute,
   no user interaction.
4. **Actual**: VPN never comes back on Android 12+ (API 31+). The user has to
   open the app before protection is active again. On Android 8–11 it
   *accidentally* worked (see §3), which is why the defect survived testing.

## 2. The intended (pre-fix) boot-restore flow

```
BOOT_COMPLETED
  → AppSystemActionReceiverAllTime.onReceive
      → checks VPN_SWITCH in Room DB
      → VpnRestartWorker.enqueue(context)          // expedited WorkManager job
          → MyVpnService.start(context)            // startForegroundService()
              → onStartCommand → startVpn() → establish()
```

## 3. Root cause (BOOT-VPN-01)

`VpnRestartWorker.enqueue()` built the work request like this:

```kotlin
val request = OneTimeWorkRequestBuilder<VpnRestartWorker>()
    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
    .setInitialDelay(2, TimeUnit.SECONDS)   // ← incompatible with setExpedited()
    .build()
```

**`setExpedited()` and `setInitialDelay()` are mutually exclusive.**
`androidx.work.WorkRequest.Builder.build()` (WorkManager 2.10.0, and every
release since 2.7.0) contains:

```kotlin
if (workSpec.expedited) {
    ...
    require(workSpec.initialDelay <= 0) { "Expedited jobs cannot be delayed" }
}
```

So `build()` threw **`IllegalArgumentException("Expedited jobs cannot be delayed")`
on every boot, on every device, every single time** — before the request was
ever enqueued. The WorkManager "VPN-05 fix" therefore never ran even once in
production.

The catch block in `enqueue()` then executed its "last-resort fallback":
`MyVpnService.start(context)` → `ContextCompat.startForegroundService()` —
directly from the `BOOT_COMPLETED` broadcast receiver:

- **Android 8–11 (API 26–30)**: boot receivers hold a temporary exemption, so
  the direct start usually succeeded. The bug was masked by the accidental
  fallback on old devices.
- **Android 12+ (API 31+)**: starting a foreground service from the background
  throws `ForegroundServiceStartNotAllowedException`. That exception was
  caught and *silently swallowed* by the catch-all in
  `MyVpnService.startForegroundServiceCompat()`.

**Net effect**: on every Android 12+ device the VPN never restarted after
reboot — and the failure was invisible because it was caught and only
`Timber`-logged at two layers. Regression confirmed by re-reading the
WorkManager 2.10.0 sources used by this project.

### Contributing weaknesses

- **No redundancy**: one broken link (a single invalid request) stranded the
  whole feature. WorkManager was the *only* start path.
- **`ExistingWorkPolicy.KEEP`**: a stale/failed unique work could block future
  restore attempts.
- **Failure invisibility**: the enqueue failure left no structured crash-log
  trace; field diagnostics showed nothing.
- The 2 s "system settle" delay intent was attached to the request — the one
  place expedited work forbids it — instead of inside the worker.
- **ACTION_STOP persistence race (BOOT-VPN-03)**: stopping the VPN from the
  notification wrote `VPN_SWITCH=false` on `serviceScope`, then called
  `stopSelf()` → `onDestroy()` cancels `serviceScope`, so the write could be
  lost — leaving `VPN_SWITCH=true` and resurrecting a VPN the user had
  explicitly turned OFF on the next boot.
- **Sticky restart without state check (BOOT-VPN-02)**: a null-intent
  `onStartCommand` (system sticky restart after kill) unconditionally called
  `startVpn()` without consulting the persisted switch.

## 4. The fix — layered, no single point of failure

Boot/app-update restore now arms **two independent paths**, both of which
run in contexts where starting a foreground service is legal on **all
supported Android versions**, and both funnel through one idempotent
operation (`VpnRestoreHelper.restoreIfEnabled`):

```
BOOT_COMPLETED / LOCKED_BOOT_COMPLETED / QUICKBOOT / MY_PACKAGE_REPLACED
  → VpnRestoreHelper.scheduleBootRestore(trigger)
      ├─ Path 1: VpnRestartWorker  (FIXED expedited WorkManager job, ~2 s)
      │            doWork(): settle-delay → restoreIfEnabled("work_manager")
      │            → verify CONNECTING/CONNECTED; retry on backoff (3 attempts)
      └─ Path 2: BootVpnRestoreAlarmReceiver  (AlarmManager one-shot, ~45 s)
                   alarm execution ⇒ app on temp allowlist ⇒ FGS start allowed
                   → restoreIfEnabled("boot_alarm") (no-op if Path 1 succeeded)

Safety nets in addition:
  • ScheduleCheckWorker (existing 15-min periodic) → reconcile:
    ensureVpnRestoreScheduledIfNeeded() re-enqueues the restore worker if the
    switch is ON but the service is down (guarded by consent check).
  • START_STICKY null-intent restart now checks VPN_SWITCH before starting
    (BOOT-VPN-02) — one more free chance to restore, without resurrection bugs.
```

### Why these two paths are reliable on every supported Android version

| Path | API 26–29 | API 30 | API 31–32 | API 33–34+ |
|---|---|---|---|---|
| Direct `startForegroundService()` from boot receiver | ✅ (exempt) | ✅ | ❌ blocked | ❌ blocked |
| **Expedited WorkManager job starting the FGS** (Path 1) | ✅ | ✅ | ✅ temp-allowlisted while job runs | ✅ |
| **AlarmManager alarm callback starting the FGS** (Path 2) | ✅ | ✅ | ✅ alarm-execution exemption | ✅ |

- **Replacing the invalid request**: no `setInitialDelay` on the expedited
  request; the settle delay moved into `doWork()` as a coroutine `delay()`.
- **REPLACE policy** (`ExistingWorkPolicy.REPLACE`) so stale work can never
  block a fresh attempt; repeated triggers collapse into one job.
- **Verified starts**: `restoreIfEnabled` polls `observableVpnState` for up
  to 8 s and distinguishes "intent sent" from "actually starting"; unconfirmed
  starts trigger WorkManager backoff retries and the backup alarm.
- **Persisted state is the source of truth, read at execution time**: every
  path re-reads `VPN_SWITCH` when it fires, so an alarm/job armed before the
  user disabled the VPN harmlessly no-ops. `VpnService.prepare()` consent is
  re-checked; if consent was revoked, the DB is synced to OFF and the user is
  notified instead of a flash-and-fail start.
- **Direct Boot**: while the device is locked (FBE), credential-protected
  storage is unavailable, so all paths early-return; `BOOT_COMPLETED` fires
  after the first unlock and re-arms the full pipeline.
- **Failure visibility**: scheduling failures now write structured crash-log
  entries (`CrashLogger`), not just logcat lines.
- **BOOT-VPN-03**: `ACTION_STOP` persists `VPN_SWITCH=false` synchronously
  (`runBlocking`, same pattern as the BUG-02 fix in `onRevoke()`) before
  `stopSelf()`, so a user-initiated stop can no longer be lost to scope
  cancellation. "What was ON before reboot comes back; what was OFF stays off"
  now holds deterministically.

### Files changed

| File | Change |
|---|---|
| `commons/utils/vpn/VpnRestoreHelper.kt` | **New.** Single idempotent restore funnel + pipeline arming + backup alarm scheduling + periodic reconcile. |
| `commons/utils/broadcastReceivers/BootVpnRestoreAlarmReceiver.kt` | **New.** AlarmManager backup start path. |
| `commons/utils/workManager/VpnRestartWorker.kt` | **Core fix.** Valid expedited request (no initial delay), REPLACE policy, settle delay inside `doWork()`, verified start with backoff retries, crash-log visibility on scheduling failure. |
| `commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt` | Boot path delegates to `VpnRestoreHelper.scheduleBootRestore`. |
| `commons/utils/broadcastReceivers/AppSystemActionReceiverAllTimeWithData.kt` | `MY_PACKAGE_REPLACED` also arms the restore pipeline (app updates kill the VPN tunnel). |
| `commons/utils/workManager/ScheduleCheckWorker.kt` | 15-min reconcile safety net. |
| `features/blockerPage/service/MyVpnService.kt` | BOOT-VPN-02 (sticky restart consults VPN_SWITCH) + BOOT-VPN-03 (synchronous stop persistence). |
| `AndroidManifest.xml` | Declares `BootVpnRestoreAlarmReceiver` (non-exported). |
| `app/build.gradle.kts` | versionCode 62→63, versionName 1.0.62→1.0.63. |
| `app/src/test/.../VpnRestartWorkerEnqueueTest.kt` | **New regression tests** (see §5). |

## 5. Verification

New unit tests (`VpnRestartWorkerEnqueueTest`, Robolectric + work-testing):

1. **Pins the platform constraint** — expedited request *with* initial delay
   must throw `IllegalArgumentException` at `build()` (guards against
   re-introduction, and flags any future WorkManager behavior change).
2. **The actual regression test** — after `VpnRestartWorker.enqueue(context)`,
   WorkManager really contains the ENQUEUED unique work (with the pre-fix
   code this list was *always empty*).
3. **Dedup** — repeated enqueues collapse into a single work item.
4. **Spec check** — the request is expedited, has zero initial delay, and a
   non-expedited out-of-quota fallback.
5. **REPLACE semantics (behavioral)** — a stale prior request under the same
   unique name is replaced, not kept.

Plus the full pre-existing suite (DAO tests, accessibility service tests,
utils, schedule evaluator …) must stay green.

Manual bring-up checklist (device/emulator):

1. Install release APK → enable VPN → confirm "Connected".
2. Reboot → without opening the app, confirm the VPN notification + tunnel
   re-appear (primary path within ~10 s of unlock/BOOT_COMPLETED; backup
   alarm within ~45 s worst case).
3. Disable VPN → reboot → confirm the VPN stays OFF (BOOT-VPN-02/03).
4. Update the APK (`adb install -r`) without reboot → VPN restored via
   MY_PACKAGE_REPLACED path.
5. `adb shell dumpsys jobscheduler | grep protect.yourself` /
   `adb shell dumpsys alarm | grep protect.yourself` to observe both armed
   mechanisms after boot.

Known platform limit (unchanged, unavoidable): if the user *force-stops* the
app, Android wipes its jobs/alarms and forbids background execution until the
app is next opened — no app can restart anything in that state.

## 6. Behavior summary

| Scenario | Before fix | After fix |
|---|---|---|
| Reboot, VPN was ON, Android 8–11 | Worked (via accidental fallback) | Works (Path 1, ~seconds; Path 2 backup) |
| Reboot, VPN was ON, Android 12+ | **Never restarted** | Works (Path 1 + Path 2, both exempt) |
| Reboot, VPN was OFF | Stayed off (mostly) | Deterministically stays off (BOOT-VPN-02/03) |
| Expedited quota exhausted | Silent failure | Backup alarm restores within ~45 s; 15-min reconcile as last resort |
| VPN consent revoked pre-reboot | Flash-and-fail start | DB synced OFF + user notified |
| Device locked at boot (FBE) | Exception, nothing scheduled | Deferred; full pipeline runs after first unlock |
| App updated (MY_PACKAGE_REPLACED) | VPN stayed down until app opened | Restore pipeline armed automatically |
