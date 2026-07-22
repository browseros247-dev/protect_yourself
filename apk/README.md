# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.69 (versionCode 69) — Accessibility Persistence + Dark Mode Contrast

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.69-release.apk` | **~3.3 MB** | Release | **Recommended for installation.** Two reported issues fixed: **(1) Accessibility Service disabled automatically** (A11Y-PERSIST-01..05): the master-switch vector — OEM/MIUI/Knox builds can flip `accessibility_enabled=0` while our entry stays in `enabled_accessibility_services`, so blocking silently died while Settings still showed ON (now **detected** via a new effective-enabled check in the 30s guard poll **and repaired** by re-writing the master switch; the ContentObserver now also watches the master-switch URI for instant reaction); unsynchronized read-modify-write races that could produce malformed `A:B:B` service lists (now `@Synchronized` canonical deduped order-preserving rewrites that only write on change); fragile exact-string component matching (now tolerant of full `pkg/pkg.Svc` vs short `pkg/.Svc` storage forms). **(2) Dark Mode contrast** (DARK-CONTRAST-01): M3 role pairings that put white text on brand oranges failed WCAG (#FF5722 error/errorContainer ≈ 3.0:1, #FF9900 tertiaryContainer ≈ 2.6:1, white label on #FF7100 tertiary ≈ 2.8:1, outline #4D7389 ≈ 3.3:1, light subtitle ≈ 4.25:1) — replaced with canonical M3 baseline pairs (7.1–13.7:1) across **both** dark and light schemes; every text-bearing role pair is now pinned ≥ 4.5:1 by automated tests. Carries v1.0.68 block-screen fixes, v1.0.67 R8 size optimization (3.3 MB release), v1.0.66 onboarding permissions. See `docs/A11Y_PERSIST_DARKMODE_REPORT_v1.0.69.md`. 375/375 tests pass. |
| `protect.yourself-v1.0.69-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.69)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** (R8 minified; size held at ~3.3 MB)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL**
- `./gradlew :app:testDebugUnitTest` → **375/375 tests pass, 0 failures, 0 errors, 0 skipped** (26 suites; +17 new: 7 `ColorContrastTest` WCAG ratio pins covering every text role pair in both schemes + 10 `AccessibilityPersistTest` Robolectric cases covering master-switch repair, exactly-once append, canonical dedupe, guardAll union idempotence, short-form matching)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `69`, versionName `1.0.69`, minSdk 26, targetSdk 35
- Post-R8 dex check (mapping-verified): `AccessibilityPersistUtils` incl. `selfHealAccessibilityService`, `isAccessibilityEffectivelyEnabled`, `isAccessibilityMasterEnabled`, `componentEntriesMatch`, `guardAllProtectedServices` all present (renamed consistently); settings keys `accessibility_enabled`/`enabled_accessibility_services` and `A11ySelfHeal`/`A11yGuard` breadcrumb strings present in the release DEX; a11y service manifest block intact (`exported=true`, `BIND_ACCESSIBILITY_SERVICE`)

## New tests (17 in v1.0.69, all passing)

`theme/ColorContrastTest` (7) — WCAG 2.1 ratio regression gate:

| Test | What it pins |
|---|---|
| `dark scheme - every text role pair meets WCAG AA 4_5_1` | All 12 text pairs (onX/X incl. error, errorContainer, tertiary, tertiaryContainer) ≥ 4.5:1 in dark |
| `dark scheme - outline on surface meets WCAG non-text 3_1` | Border #5D7E93 on #151F26 ≥ 3.0:1 |
| `dark scheme - pins the v1_0_69 contrast-corrected role colors` | Exact M3 values (#FFB4A9/#690005, #93000A/#FFDAD6, #6F3A00/#FFDCC2, #3B2700, #5D7E93) |
| `light scheme - every text role pair meets WCAG AA 4_5_1` | Same 12 pairs in light (incl. darkened subtitle #64707E ≈ 4.7:1) |
| `light scheme - outline on surface meets WCAG non-text 3_1` | ≥ 3.0:1 |
| `light scheme - pins the v1_0_69 contrast-corrected role colors` | Exact M3 values (#BA1A1A/white, #FFDAD6/#410002, #FFE0C8/#311300) |
| `documented exception - brand orange with white bold label stays above floor` | Brand-gradient button pair pinned at ≥ 2.7:1 tripwire (known large-text deviation, documented) |

`features/protectedApps/AccessibilityPersistTest` (10, Robolectric sdk=34):

| Test | What it pins |
|---|---|
| `effective check - true when entry present and master on` | Healthy state reports effectively enabled |
| `effective check - FALSE when entry present but master flipped off` | **A11Y-PERSIST-03 detection** — entry-only check true, effective check false |
| `selfHeal - repairs master switch when entry present but master flipped` | **A11Y-PERSIST-03 repair** — master restored to 1, service list left byte-identical (no churn) |
| `selfHeal - appends own entry exactly once when missing` | Order preserved, other services untouched, second call is a no-op |
| `selfHeal - dedupes racing duplicates into canonical single entries` | Malformed `other:own:other:own` rewritten to `other:own` + master repaired |
| `guardAllProtectedServices - canonical union, idempotent, no duplicates` | 3rd-party protected services appended once; short-form pre-seed not duplicated |
| `isOwnServiceEnabled - matches the SHORT storage form` | **A11Y-PERSIST-02** — `pkg/.Svc` recognized |
| `componentEntriesMatch - full vs short form` | Both directions match; identity matches |
| `componentEntriesMatch - rejects different or malformed components without throwing` | Null/blank/garbage safe |
| `selfHealSafe - never throws and never truncates foreign entries` | No-clobber guarantee |

## Manual device checklist (post-install)

1. Enable VPN → confirm "Connected".
2. Reboot → do NOT open the app → VPN notification + tunnel re-appear (WorkManager path within seconds of BOOT_COMPLETED/unlock; backup alarm within ~45 s worst case).
3. Turn VPN off → reboot → VPN stays OFF.
4. App update (`adb install -r`) without reboot → VPN restored via `MY_PACKAGE_REPLACED` path.
5. **Accessibility persistence**: enable the service → `adb shell settings put secure accessibility_enabled 0` (simulates the OEM master-switch flip) → within ≤ 30 s the guard restores it (`adb shell settings get secure accessibility_enabled` → `1`) and blocking keeps working.
6. **Dark Mode**: enable Dark theme → open Settings → Blocker home (status cards), App Lock setup, Schedule editor, Backup & Restore, Crash Log → every card/label/button clearly readable (no dark-on-dark or white-on-orange text).

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
