# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.74 (versionCode 74) — Field-bug RCA round: accessibility self-disable + VPN ("DNS") dying (OEM background policing)

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.74-release.apk` | **~3.27 MB** | Release | **Recommended for installation.** Fixes the two field-reported bugs. **(1) A11Y-SELFDISABLE-01 — "Accessibility Service turns itself off 1–5 s after enabling" (and the suspected "Prevent Uninstall" trigger).** Full root-cause analysis: the app's own kill vectors are provably closed — since v1.0.70 no draw-over window can cover accessibility-management screens, the device-admin (Prevent Uninstall) toggle path was audited and never touches accessibility settings, and the keyword-matching engine scores **0 hits** against the app's own accessibility description. The root cause is **OEM background-process policing** (vivo/Funtouch class and MIUI/ColorOS/EMUI families): the system kills "non-autostart" apps within seconds and, on several vivo builds, scrubs the app's entry from enabled-accessibility-services; enabling/disabling device admin makes the OEM security service re-evaluate the app — which explains both the 1–5 s delay and the observed Prevent-Uninstall correlation without any app-code linkage. Mitigations: new RECOMMENDED onboarding row **"Auto-start (OEM)"** (only on affected manufacturers) deep-linking into the OEM autostart/background-startup manager via a candidate chain (vivo `BgStartUpManagerActivity` → iQOO Secure; MIUI `AutoStartManagementActivity`; ColorOS/Oplus startup lists; EMUI startup + protected-apps; always ending at the guaranteed app-details screen), plus an in-banner **"Fix auto-kill (OEM background setting)"** CTA on the accessibility warning card on managed devices. **(2) VPN-RESUME-01 — "DNS automatically disabling, sometimes".** The VPN service was killed by the same OEM policing while the app was backgrounded, and the only restore triggers were boot, connectivity changes, and the 15-min WorkManager reconcile (unreliable under exactly these killers). Fix: `MainActivity.onResume` in the MAIN state now runs the same idempotent `VpnRestoreHelper.restoreIfEnabled(..., "foreground_resume")` used by the boot path — it no-ops when the switch is off or the VPN is up/starting, and re-syncs the switch if VPN consent was revoked. Carries v1.0.73 (CrashLogger thread-safety + IPC caching), v1.0.72 and all earlier rounds. See `docs/A11Y_SELFDISABLE_VPN_REPORT_v1.0.74.md`. **479/479 tests pass.** |
| `protect.yourself-v1.0.74-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.74)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 minified; 3,274,757 bytes — byte-size coincidence with v1.0.73, sha256 differs), built BEFORE the test run per the release-first process
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,296,115 bytes)
- `./gradlew :app:testDebugUnitTest` → **479/479 tests pass, 0 failures, 0 errors, 0 skipped** (39 suites; +21 vs v1.0.73: new `OemBackgroundUtilsTest` 12, new `OemMitigationWiringTest` 5, `OnboardingPermissionsTest` 14→18)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `74`, versionName `1.0.74`, minSdk 26, targetSdk 35
- Post-R8 mapping check → `OemBackgroundUtils` retained and fully mapped; `MainActivity.reconcileVpnOnForeground` compiled into `onResume`; `OnboardingPermissions$Kind.BACKGROUND_AUTOSTART` present in the enum mapping

## New tests (21 in v1.0.74, all passing)

`commons/utils/permissionUtils/OemBackgroundUtilsTest` (12):
manufacturer detection matrix (managed set incl. vivo/iqoo/MIUI/ColorOS/EMUI aliases; case/whitespace tolerance; **Samsung/Google etc. explicitly excluded** — Samsung uses the standard battery-optimization model already covered by the existing onboarding row), per-OEM candidate-chain order and class names, **app-details fallback always last and always present** (even for unmanaged manufacturers), `openAutostartSettings` launch smoke test, ack-prefs default-false + round-trip.

`commons/utils/permissionUtils/OemMitigationWiringTest` (5; static pins):
onboarding BACKGROUND_AUTOSTART kind/row/live-evaluate wiring (row gated on `autostartApplicable`), MainActivity dispatch branch + "Open" label + ack persistence, VPN foreground reconcile in `onResume` (MAIN state) via `restoreIfEnabled(applicationContext, "foreground_resume")` on `Dispatchers.IO`, warning-banner CTA on managed devices, OEM-utility no-crash contract.

`OnboardingPermissionsTest` (+4, now 18):
autostart row present on managed device (RECOMMENDED, ungranted until ack, **does not block the mandatory gate**), renders granted after acknowledgement, omitted on unmanaged devices (ack cannot resurrect it), legacy 5-arg `buildRows` calls unchanged (4 rows).

## Manual device checklist (post-install)

1. **Affected OEM (vivo/iQOO/Xiaomi/Oppo/Realme/OnePlus/Huawei/Honor):** fresh install → onboarding shows the **"Auto-start (OEM)"** row (Recommended) → tap **Open** → enable auto-start/background running for Protect Yourself in the OEM screen → row shows granted on return. Re-enable the accessibility service afterward: it must stay on (no 1–5 s self-disable).
2. **Same device:** with VPN/"DNS" ON, background the app for a while, then reopen it → if the system killed the VPN, it is silently restored on foregrounding (VPN key icon returns); the DNS switch state is consistent.
3. **Unaffected device (e.g. Samsung/Google):** onboarding shows exactly the 4 previous rows — no autostart row.
4. Regression sweep: mandatory onboarding gate still blocks "Continue to App" until Required rows are granted (autostart row never blocks); accessibility warning banner still deep-links to the service page on tap; Prevent Uninstall toggle behaves exactly as before (its code path is untouched); all blocking/VPN/schedules/focus/lock behavior unchanged.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
