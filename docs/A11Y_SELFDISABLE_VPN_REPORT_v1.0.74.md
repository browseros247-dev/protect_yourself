# A11Y Self-Disable + VPN ("DNS") Dying — Root-Cause Analysis & Fix Report (v1.0.74 / versionCode 74)

**Date:** 2026-07-23 · **Branch:** `fix/a11y-selfdisable-vpn-v1.0.74` · **Base:** v1.0.73 branch tip (`11d46bc`, PR #8) — stack-merge #8 then this PR (#9) in order
**Standing order honored:** release APK built and verified **first**, full test suite **after**, commit/push only after both green.

---

## 1. Executive summary

| # | Field report (verbatim) | Root cause | Fix ID | Fix |
|---|---|---|---|---|
| 1 | "Accessibility Service turns itself off a few seconds (1–5 s) after I enable it. I suspect enabling then disabling 'Prevent Uninstall' may cause it — not confirmed." | **OEM background-process policing** (vivo/Funtouch class; also MIUI/ColorOS/EMUI families) kills "non-autostart" apps within seconds and, on vivo builds, scrubs the app's entry from `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`. **No app-code linkage to Prevent Uninstall exists** (§3) — the correlation is real but environmental: toggling device-admin makes the OEM security service re-evaluate the app. | OEM-BG-01 | Onboarding row **"Auto-start (OEM)"** (RECOMMENDED, managed devices only) deep-linking to the OEM autostart manager; ack persistence so the row completes. |
| 1b | (same — in-app surface) | Same root cause; re-enabling from the banner alone is an infinite loop on these devices. | OEM-BG-02 | **"Fix auto-kill (OEM background setting)"** CTA inside the accessibility warning banner on managed devices. |
| 2 | "Sometimes DNS automatically disabling." | The local-DNS **VPN service is killed by the same OEM policing** while the app is backgrounded. Pre-v1.0.74 restore triggers were: boot receiver, backup alarm, connectivity-change receiver, 15-min WorkManager reconcile — all unreliable under exactly these killers (WM jobs are delayed/deferred; connectivity rarely changes). **`MainActivity.onResume` did not reconcile VPN state at all** → DNS stayed off until manual toggle. | VPN-RESUME-01 | `onResume()` in MAIN state runs the **same idempotent `VpnRestoreHelper.restoreIfEnabled(ctx, "foreground_resume")`** used by the boot path. |

**Honesty limits:** Android exposes **no API to query or force** an OEM autostart whitelist. The app cannot programmatically prevent the OEM kill; it can (a) send the user to the exact OEM screen (done, with per-OEM deep-link chains), (b) self-heal the accessibility entry when `WRITE_SECURE_SETTINGS` was granted via ADB (already in place since v1.0.69), and (c) restore the VPN at every reliable opportunity (now including every foregrounding). On stock/Pixel/Samsung devices none of this applies and behavior is unchanged.

---

## 2. Issue 1 investigation — what was checked and refuted

Before concluding OEM policing, every **app-internal** way an accessibility service can be turned off was audited:

### 2.1 Draw-over→auto-disable class (the v1.0.70 kill vector) — verified CLOSED

Android auto-disables an accessibility service minutes/seconds after the app draws over its own accessibility-management screens (the system treats it as tapjacking). Verified closed:

| Layer | Mechanism | Status |
|---|---|---|
| Event guard | `onAccessibilityEvent` early-returns on `ProtectedSystemScreens.isAccessibilityManagementScreen(pkg, class)` (settings packages + `"accessibility"/"installedservices"/"servicedetails"` class-name markers) | ✅ active |
| Text fingerprint | `isAppInfoPageUnsafe` layer-2 exemption `isOurAccessibilityServicePage` (probes the service-description text "In order to block adult content" in the node tree) — no block window over our own service page | ✅ active |
| Block-activity launch | `launchBlockActivity` re-checks `isAccessibilityManagementScreen` before showing | ✅ active |
| Settings-page (SET) path | fires only on user-configured `cachedSettingTitles` — not a default vector | ✅ not applicable |

### 2.2 Self keyword-matching — refuted with a 0-hit simulation

Hypothesis: the porn-keyword engine matches our own service description ("In order to block adult content on your device…") and "blocks" the settings app. Refuted: `preset_block_keywords.json` (1,189 tokens after comma-splitting 37 mega-strings) was matched against the exact `accessibility_service_description` string with the production normalization + word-boundary/substring matcher (`KeywordMatcher.findFirst` + `normalizeForMatching`): **0 hits** (`adult` as a standalone token is not in the preset list).

### 2.3 Prevent Uninstall (device-admin) path — audited, no linkage

- PU ON → `RequestDeviceAdmin` → system `ACTION_ADD_DEVICE_ADMIN` page (hosted by `com.android.settings`); on result, only `onDeviceAdminResult` persists the switch.
- PU OFF → `removeActiveAdmin` + `refreshBlockingConfig()`.
- **Nothing in either path writes accessibility settings, starts/stops the service, or draws windows.** The manifest service declaration and `accessibility_setting.xml` are normal (`canRetrieveWindowContent=true`, standard flags).
- Verdict for the user's question "*are the two events related?*": **Not in app code.** The plausible real-world link is environmental: on vivo/Funtouch, granting or revoking device-admin triggers a security re-scan of the app; if the app is not autostart-whitelisted, the re-scan is when the background killer acts — matching the user's observed (unconfirmed) correlation and the 1–5 s timing.

### 2.4 Self-heal gap — why the existing guard couldn't save the user

`AccessibilityGuard` (ContentObserver on `ENABLED_ACCESSIBILITY_SERVICES` + 30 s poll) re-arms the entry **only when `WRITE_SECURE_SETTINGS` is granted** (ADB lineage); otherwise it can only post a 1 h-throttled notification. A field device without WSS (the common case) gets no re-arm — the OEM scrub sticks.

### 2.5 Root cause — OEM background policing

vivo/Funtouch (and MIUI, ColorOS, EMUI) ship a proprietary "background startup/ autostart" permission, separate from Android battery optimization. Apps missing it are killed within seconds even while bound (a11y/VPN), and some vivo builds also clean up the accessibility-services setting entry. This explains **both** field symptoms and the timing distribution.

---

## 3. Issue 2 investigation — VPN restore-trigger inventory (pre-fix)

| Trigger | Class | Reliable under OEM killers? |
|---|---|---|
| Boot + backup alarm | `BootVpnRestoreAlarmReceiver` | Only at boot / alarm fire |
| Expedited worker w/ retries | `VpnRestartWorker` (REPLACE, consent-checked) | Only when enqueued by an above trigger |
| Connectivity change | `AppSystemActionReceiver` (foreground-only) | Rarely fires; foreground-only |
| Periodic reconcile | `ScheduleCheckWorker` 15-min | Deferred/batched by OEM power managers — the exact failure mode |
| **App foregrounding** | **— none —** | **GAP closed by VPN-RESUME-01** |

Result: after a kill with the app backgrounded, "DNS" could stay off indefinitely until the user toggled it. Matches "sometimes DNS automatically disabling".

---

## 4. Fixes implemented

### OEM-BG-01 — New `OemBackgroundUtils` + onboarding row
- `commons/utils/permissionUtils/OemBackgroundUtils.kt` (new):
  - `isAutostartManagedDevice(manufacturer)` — managed set: **vivo, iqoo, xiaomi, redmi, poco, oppo, realme, oneplus, huawei, honor** (trim+lowercase). **Samsung deliberately excluded** — One UI uses the standard battery-optimization model already covered by OB-PERM-02; its per-app switches do not gate a11y/VPN survival.
  - `autostartCandidates(context, manufacturer)` — OEM deep-link chain: vivo→`com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`→`com.iqoo.secure/.MainActivity`; MIUI family→`com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity`; ColorOS family→`com.coloros.safecenter` startup lists (2 variants)+`com.oplus.safecenter`; EMUI family→`com.huawei.systemmanager` startup + protected-apps. **Last candidate is always `Settings.ACTION_APPLICATION_DETAILS_SETTINGS`** (guaranteed launchable).
  - `openAutostartSettings(context): Boolean` — tries candidates in order; every failure logged+swallowed (no-crash contract).
  - Ack prefs (`oem_background_prefs` / `autostart_hint_acknowledged`) — the OEM toggle state is not queryable; "granted" = "user was sent to the screen".
- `OnboardingPermissions`: `Kind.BACKGROUND_AUTOSTART`; `buildRows(...)` gains **trailing default params** `autostartApplicable=false, autostartAcknowledged=false` (source-compatible with all existing callers/tests); row appended only when applicable — RECOMMENDED, so it **never gates** the mandatory OB-ENFORCE-01 gate; `evaluate()` passes live detection + ack (fail-closed to "row hidden").
- `MainActivity.handleOnboardingPermissionAction`: new branch → open + ack + breadcrumb + toast fallback; label "Open"; icon `PlayArrow`.

### OEM-BG-02 — warning-banner CTA
- `BlockerPageHome.kt AccessibilityWarningCard` (error branch): on managed devices, extra **TextButton "Keeps turning off by itself? Fix auto-kill (OEM background setting)"** → open + ack (+ toast fallback). Unaffected devices see no change.

### VPN-RESUME-01 — foreground VPN reconcile
- `MainActivity.reconcileVpnOnForeground()` called from `onResume()` when `appState == MAIN` → IO coroutine → `VpnRestoreHelper.restoreIfEnabled(applicationContext, "foreground_resume")`, try/caught. Reuses the boot-path logic verbatim: locked-device skip; switch-OFF no-op; **CONNECTING/CONNECTED/running no-op (no overlapping starts)**; consent-revoked → sync switch OFF + notify; verify-window start. Worst case per foregrounding: one cheap state read.

---

## 5. Verification

### 5.1 Release-first build validation (STANDING ORDER)

| Step | Result |
|---|---|
| `:app:assembleRelease` | **BUILD SUCCESSFUL** (~5 min; R8) — built BEFORE tests |
| `apksigner verify` release APK | **signature valid** |
| `aapt2 dump badging` | `protect.yourself` **versionCode 74 / versionName 1.0.74**, minSdk 26, targetSdk 35 |
| Release size | 3,274,757 B (byte-size coincidence with v1.0.73; sha256 `ec721c…d857` ≠ v1.0.73 `aba21b…dbe2`) |
| R8 mapping pins | `OemBackgroundUtils` retained+mapped (49 refs); `reconcileVpnOnForeground()` inlined into `onResume` (mapping line present); `OnboardingPermissions$Kind BACKGROUND_AUTOSTART` in enum mapping |
| Compile fix during build | `Icons.Filled.PlayArrow` needed an explicit import (this module imports every icon individually) — fixed, rebuilt |

### 5.2 Full test suite (run after the release build)

`:app:testDebugUnitTest` → **479/479 pass, 0 failures, 0 errors, 0 skipped — 39 suites**

| New/extended suite | Tests | Pins |
|---|---|---|
| `OemBackgroundUtilsTest` (new) | 12 | manufacturer matrix incl. case/whitespace + Samsung/Google exclusion; per-OEM chain order+class names; app-details fallback always last & always present (incl. unmanaged OEMs); launch smoke; ack default-false + round-trip |
| `OemMitigationWiringTest` (new, static) | 5 | onboarding kind/row/evaluate wiring + `if (autostartApplicable)` gate; MainActivity branch+label+ack; `reconcileVpnOnForeground`+`"foreground_resume"`+`restoreIfEnabled`+`Dispatchers.IO`; banner CTA; OEM-util no-crash `catch (t: Throwable)` contract |
| `OnboardingPermissionsTest` (extended 14→18) | +4 | row present/managed (RECOMMENDED, ungranted-until-ack, gate unaffected, `missingKinds`); granted-after-ack; omitted when unmanaged (ack can't resurrect); legacy 5-arg call unchanged |
| Existing 36 suites | 458 | **unchanged, all green** — no regressions |

Suite count: 458 (v1.0.73) + 12 + 5 + 4 = **479** ✓

### 5.3 Functionality-preservation / no-side-effect argument

| Risk | Mitigation / evidence |
|---|---|
| Mandatory onboarding gate loosened/tightened | Row is RECOMMENDED; `allRequiredGranted` unchanged and tested (`autostart row present … does not block`; legacy call test). OB-ENFORCE-01 behavior identical on unmanaged devices (row absent). |
| Extra work per `onResume` | Guarded to MAIN state; single suspend call already used by boot path; internal no-op fast paths (switch off / running / connecting) — no UI-thread work (IO dispatcher). |
| Overlapping VPN starts | `restoreIfEnabled` treats CONNECTING/CONNECTED as running (VPN-STATE-05 logic) — no duplicate start intents. |
| Crash surface in OEM deep-links | Every `startActivity` inside `openAutostartSettings` wrapped; candidates ordered with guaranteed launchable app-details last; callers toast on total failure. |
| Compose `when` exhaustiveness on new enum | All three `when (row.kind)` sites updated (icon, label, dispatch) — full-compile verified. |
| Prefs write cost | One boolean, on user tap only. |
| Unmanaged devices | No UI change (row hidden, banner CTA hidden); `OnboardingPermissions` defaults keep all pre-v1.0.74 call sites compiling+behaving (tested). |

---

## 6. What this round deliberately did NOT do

- **No accessibility self-re-enable shortcut** (e.g., abusing overlays/settings automation): impossible without WSS on affected builds; the WSS self-heal path already exists for power users (unchanged).
- **No change to Prevent Uninstall code paths** — audited clean; changing them would add risk without addressing the (external) cause.
- **Samsung not added** to the managed set — different (standard) model; adding it would nag users with a useless row.
- **No `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`-style direct grant** for autostart — no such API exists.

## 7. Known remaining limitations

1. On affected OEMs, **survival ultimately depends on the user flipping the OEM autostart toggle** — the app can only deep-link + remind (banner CTA) + heal when WSS-granted.
2. Between OEM kill and next foregrounding, VPN/DNS can still be down (nothing can run while killed); the foreground reconcile minimizes the user-visible window.
3. Accessibility entry scrubs on non-WSS devices still require manual re-enable — the banner + OEM CTA now explain *why* and offer the durable fix.
