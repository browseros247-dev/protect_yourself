# Accessibility Auto-Disable Fix — v1.0.38

**Date**: 2026-07-11
**Commit**: `c5eb879` on branch `analysis-reference-20260711`
**APK**: `apk/protect.yourself-v1.0.38-release-accessibility-fix.apk` (16 MB)
**Reference**: v1.0.53 (decompiled with jadx 1.5.1)

## Problem

The accessibility service was being auto-disabled by Android OEMs every 12–48 hours. The existing 30-second polling `AccessibilityGuard` could detect the disable but could only post a notification — it could not re-arm the service programmatically on Android 13+.

## Root Cause

The previous `AccessibilityPersistUtils` was a **stub** that just logged. The reference has a real implementation that writes directly to `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` + sets `ACCESSIBILITY_ENABLED=1`, but only if `WRITE_SECURE_SETTINGS` is granted via ADB.

## Fix (ported from the reference)

### New files
- `AccessibilityPersistUtils.kt` — real self-heal implementation (replaces stub)
- `WriteSecureSettingsSetupPage.kt` — Compose UI wizard for the one-time ADB grant
- `ProtectedAppsRegistry.kt` — SharedPreferences registry of protected services

### Rewritten files
- `AccessibilityGuard.kt` — adds `ContentObserver` for instant re-arm (vs 30s polling)
- `ProtectedAppsActivity.kt` — full Compose UI (was a stub)

### Integration points (matching the reference pattern)
`selfHealSafe(context)` is now called from:
- `ProtectYourselfApp.onCreate()`
- `MainActivity.onResume()`
- `MyAccessibilityService.onServiceConnected()`
- `MyAccessibilityService.onUnbind()` ← **critical** (fires before disable)
- `MyAccessibilityService.onDestroy()`
- `AppSystemActionReceiverAllTime.onReceive()`
- `AppSystemActionReceiverAllTimeWithData.onReceive()`
- `AppDataCheckWorker.doWork()` (24h safety net)

### Additional fixes
- `MyAccessibilityService.onDestroy` now cancels `serviceScope` (P0 leak fix)
- `PackageManagerProvider` exposes `getApplicationContext()` + `getPackageName()`
- `BlockerPageHome` adds a "Reliable Accessibility" category card

## How the user enables it

1. Open the app → tap **Reliable Accessibility** on the home screen
2. Follow the 4-step wizard:
   - Install Android Platform Tools on a computer
   - Enable USB debugging on the phone
   - Run: `adb shell pm grant protect.yourself android.permission.WRITE_SECURE_SETTINGS`
   - Tap "Verify" — the app confirms the grant and immediately runs a self-heal cycle
3. From now on, if Android removes the accessibility service, the app re-arms it within milliseconds via the `ContentObserver`. The 30-second polling and 24-hour worker are fallback safety nets.

## Verification

- ✅ Release APK builds successfully (3m 17s)
- ✅ `WRITE_SECURE_SETTINGS` permission confirmed in manifest
- ✅ All new methods (`selfHealAccessibilityService`, `isWriteSecureSettingsGranted`, `guardAllProtectedServices`, `ownComponentFlat`) confirmed in DEX
- ✅ `ProtectedAppsActivity` declared in manifest (not exported)
- ⏳ Manual testing on real devices required (Pixel / MIUI / One UI / EMUI / Color OS)

## What's NOT changed

- No push to `main` branch (per user instruction)
- No source-code changes outside the accessibility fix scope
- All previous analysis docs in `docs/analysis-v1.0.37/` are preserved
