# PU Settings-Page Protection — RCA & Fix Report (v1.0.75 / versionCode 75)

**Date:** 2026-07-23 · **Branch:** `fix/pu-settings-protection-v1.0.75` · **Base:** `main` @ `17a0480` (v1.0.74 merged, PR #8)
**Standing order honored:** release APK built and verified **first**, full test suite **after**, commit/push only after both green.

---

## 1. Executive summary

| # | Requirement | Protection gap (RCA) | Fix ID | Mechanism |
|---|---|---|---|---|
| 1 | Block access to the app's **own Accessibility Service settings page** while Prevent Uninstall is on | PU never targeted it; worse, the v1.0.70 A11Y-KILL-01 fix **explicitly exempted** that page (OS auto-disables services whose windows cover a11y screens) | PU-A11Y-PAGE-01 | **Eviction, not obstruction**: throttled `GLOBAL_ACTION_HOME` + delayed toast + self-heal backstop; detail-page-only detection |
| 2 | Block access to the system **VPN settings page** (Settings app & Quick Settings panel) while Prevent Uninstall is on | No check existed for VPN screens; the VPN app-detail dialog was only *incidentally* matched by PU's SubSettings rule | PU-VPN-01 | Standard PU block screen (`launchBlockActivity`) with a new VPN-specific message; safe because the OS kill-switch only protects a11y screens |

Both protections are gated strictly on `isPreventUninstallOn` and fire only for other packages' windows (ours and `com.android.systemui` excluded). With PU off, event flow is byte-identical to v1.0.74.

---

## 2. RCA details

### 2.1 Gap 1 — own accessibility-service page

Path to defeat protection pre-v1.0.75 (PU on): Settings → Accessibility → Protect Yourself → toggle OFF. When the toggle flips, the service dies — every PU/feature check stops instantly, and the device-admin alone cannot stop an uninstall (Play/system never asks device-admin on all OEM paths; the a11y service is the enforcement layer).

Why it was unprotected: the v1.0.70 "Check 4 REMOVED" change deliberately stopped blocking that page because **covering an accessibility-management screen makes Android disable the covering service within ~1–5 s** (anti-tapjacking/consent integrity). Any overlay/activity solution re-opens the exact kill vector from the user's field report in v1.0.74.

**Resolution:** eviction instead of covering. `GLOBAL_ACTION_HOME` draws no window, so it cannot trigger the OS kill. Self-heal (v1.0.69, WSS lineage) remains as the backstop if the toggle is still flipped before eviction lands.

Detection-precision problem (solved): the accessibility services LIST row shows our **summary** (`accessibility_service_summary`), which is a verbatim prefix of our **description** — so the pre-existing `isOurAccessibilityServicePage` fingerprint cannot tell "list" from "detail". New `detailOnlyFingerprint(descNorm, summaryNorm)` = the description suffix after the summary ("protectyourselfwillalsobeabletoblock…"), present only where the full description renders = the detail page. Events are further scoped to a11y-management contexts (class markers or the generic `SubSettings` host), the probe is throttled to 400 ms, the HOME press to 1.5 s, and the toast to 20 s — the v1.0.71 ANR budget (90 ms per event) is unaffected.

Enable-flow safety: while the service is **off**, zero accessibility events flow → the eviction cannot obstruct *enabling*. It only acts once the service is alive (i.e., protection is active).

### 2.2 Gap 2 — system VPN settings page

Path to defeat DNS protection pre-v1.0.75 (PU on): Settings → Network & internet → VPN → gear next to Protect Yourself → Disconnect/Forget, or revoke consent; also Quick Settings VPN tile long-press (opens the same `VpnSettingsActivity`). `MyVpnService` dies; per v1.0.74, only the 15-min WorkManager reconcile or a foreground `restoreIfEnabled` brings it back — neither stops a determined user.

Coverage matrix (class-name based, settings packages only):

| Entry point | Foreground class | Matched |
|---|---|---|
| Settings → Network → VPN (AOSP 12–15) | `com.android.settings.Settings$VpnSettingsActivity` | ✅ |
| Android 13/14 network rework host | `…network.vpn.VpnSettingsActivity` | ✅ |
| Legacy host (`vpn2.VpnSettings` fragment) | `Settings$VpnSettingsActivity` | ✅ |
| Quick Settings VPN tile long-press | same activity as above | ✅ |
| MIUI / vivo / ColorOS VPN page | OEM `…Vpn…` activity in a settings package | ✅ |
| Network overview page (`NetworkDashboardActivity`) | class has no `vpn` marker | ❌ stays reachable (deliberate) |

Text-based matching was rejected: the Network overview's event text mentions "VPN" (the settings row), which would over-block a page the requirement doesn't cover.

### 2.3 Message UX

- VPN block screen message (new string `pu_blocked_vpn_settings_message`): *"Prevent Uninstall is on. VPN settings are blocked to keep DNS protection active."* — the PU block screen's 3 s countdown + Close behavior (v1.0.68) is reused unchanged.
- A11y eviction toast (new string `pu_blocked_a11y_page_toast`), posted 700 ms after the HOME press over the launcher — never over the a11y page (zero-obscure discipline).

---

## 3. Implementation inventory

| File | Change |
|---|---|
| `features/protectedApps/ProtectedSystemScreens.kt` | `isVpnSettingsScreen()` (settings-package + `vpn` class marker, blank-safe) and `detailOnlyFingerprint()` (description-minus-summary suffix, fail-open) — pure/unit-tested |
| `features/blockerPage/service/MyAccessibilityService.kt` | pre-gate `evictFromOurA11yServicePage()` call before the A11Y-KILL-01 early-return; new VPN check inside the PU gate (before `isAppInfoPage`); `isOurA11yServiceDetailPage()` two-layer detect; `showPuEvictionToast()`; throttle constants `A11Y_PAGE_PROBE_THROTTLE_MS=400`, `A11Y_PAGE_KICK_THROTTLE_MS=1500`, `PU_KICK_TOAST_DELAY_MS=700`, `PU_KICK_TOAST_THROTTLE_MS=20000` (+ `@Volatile` state) |
| `res/values/strings.xml` | `pu_blocked_vpn_settings_message`, `pu_blocked_a11y_page_toast` |
| `app/build.gradle.kts` | versionCode 75 / versionName 1.0.75 |

Event ordering (PU on, settings window event):
1. **PU-A11Y-PAGE-01** eviction (ours only; other packages) — consumes event on our detail page.
2. **A11Y-KILL-01** guard — any other a11y-management screen: self-heal + return (unchanged).
3. PU gate: **PU-VPN-01** `isVpnSettingsScreen` → block screen; then `isAppInfoPage` (app-info/uninstaller/device-admin, unchanged).
4. Existing SET/keyword/browser checks (unchanged).

Reliability chain for the VPN block screen is inherited whole: BAL-drop verification after 900 ms, one retry, HOME last-resort, 300 ms global launch throttle.

---

## 4. Verification

### 4.1 Release-first build validation

| Step | Result |
|---|---|
| `:app:assembleRelease` | **BUILD SUCCESSFUL** (~8.3 min; R8) — built BEFORE tests |
| `apksigner verify` | signature valid |
| `aapt2 dump badging` | `protect.yourself` **75 / 1.0.75**, minSdk 26, targetSdk 35; 3,275,061 B |
| R8 mapping pins | `evictFromOurA11yServicePage`, `showPuEvictionToast`, `isVpnSettingsScreen`, `detailOnlyFingerprint` present |
| One build-fix cycle | none for release; test-only fix (pin lookback window) after first test run |

### 4.2 Full suite (run after the release build)

`:app:testDebugUnitTest` → **491/491 pass, 0 failures, 0 errors, 0 skipped — 40 suites**

| Suite | Tests | Pins |
|---|---|---|
| `PuSettingsProtectionWiringTest` (new) | 7 | eviction precedes A11Y-KILL-01 guard + PU-gated; HOME-only (no `launchBlockActivity`) on a11y page; toast delayed+throttled; detail-fingerprint + scope pins; VPN block in PU gate with correct message key; helper semantics; resources exist |
| `ProtectedSystemScreensTest` (11→16) | +5 | VPN class matrix + rejections (Network overview/non-settings/blank/our app); detail fingerprint vs list summary; embedded-text match; junk-input fail-open |
| Existing 38 suites | 479 | unchanged, all green — no regressions |

Math: 479 + 7 + 5 = **491** ✓. (One test-run iteration: a static pin's lookback window (500→1200 chars) didn't reach the `isPreventUninstallOn` gate across the comment block — test-only fix, no production change.)

### 4.3 Functionality-preservation analysis

| Risk | Mitigation / evidence |
|---|---|
| Re-triggering the v1.0.70 service-kill | Zero windows over a11y screens: eviction path uses no activity/overlay; toast delayed until Settings is closed; static test pins "no `launchBlockActivity` in the eviction method". `launchBlockActivity`'s choke-point guard retained. |
| Blocking the a11y services LIST / other apps' services | Detail-only fingerprint (list shows summary ≠ marker); tests pin summary-rejection; a11y-context scoping. |
| Blocking the Network overview page | Class-only VPN matching; test pins NetworkDashboard rejection. |
| Obstructing first-time enable / re-enable after OEM kill | Service dead ⇒ no events ⇒ no eviction; documented in report; PU gate requires the (alive) service. |
| Event-loop cost (ANR budget) | String checks only on settings packages; node probe ≤ 1/400 ms; HOME ≤ 1/1.5 s; pure-Kotlin helpers unit-tested. |
| PU-off behavior | Both checks are short-circuited by `isPreventUninstallOn` before any work; event flow identical to v1.0.74. |
| Self-heal regression | Unchanged; additionally invoked as backstop inside the eviction path. |

## 5. Deliberate non-goals

- No blocking of the services **list** page (out of requirement scope; needed for other apps).
- No text-matching for VPN ("Network & internet" overview mentions VPN).
- No change to PU device-admin/uninstall logic, the SET title rules, or draw-over strategy.

## 6. Known limitations

1. Eviction is reactive: a user with very fast hands might see the toggle for a split second before HOME fires; if the service were killed in that window (not reproducible on tested flows: the toggle first shows a confirmation dialog), WSS self-heal or the next-screening detection would restore/guard it. The device-admin itself is separately protected by the pre-existing PU checks (its deactivation page is blocked).
2. OEM VPN pages whose activity class name contains no `vpn` substring in a non-settings package are not covered (fail-open; no false positives possible).
3. Quick Settings **tile** itself (systemui) is not blocked — its long-press target (the settings page) is.
