# Latest APK

This directory contains the **latest** pre-built, signed APK of **Protect Yourself**.

## Policy

- **Only the latest version** is kept here. When a new version is built, the previous APKs are removed.
- Each version has two files: `*-debug.apk` (debug build with logging) and `*-release.apk` (production build, recommended).

## Current Version: 1.0.71 (versionCode 71) — Field crash-log audit: a11y ANRs, WARN spam, breadcrumb NPE

| File | Size | Build Type | Description |
|---|---|---|---|
| `protect.yourself-v1.0.71-release.apk` | **~3.46 MB** | Release | **Recommended for installation.** Fixes from a full audit of a real crash export (vivo V2206, Android 14, 49 entries): **(1) 22 FATAL ANRs, main thread blocked 5–12 s** (A11Y-ANR-01): every accessibility event triggered a recursive node-tree walk whose per-node `getChild()` call is a synchronous binder IPC into the remote process — guarded only by node COUNTS (300/500) with a "~2 ms per IPC" assumption that collapses under load (10–50 ms/call ⇒ 5–15 s walks; 7 more ANRs stalled in the twice-per-event `getRootInActiveWindow` fetch itself). Fixed with a **hard time+node traversal budget** (`TraversalBudget`: 150 nodes / 90 ms, injected clock), lower caps (depth 50→12, nodes 300/500→150), a **350 ms per-package heavy-scan throttle** that collapses Chrome render bursts to one scan, and a **single shared root fetch** per scan — URL scraping order (SafeSearch first), content-text keyword matching, and all block behavior are unchanged. **(2) "Invalid block-screen countdown stored (0)" persisted as a crash entry on every read** (SET-COUNTDOWN-01): the DB seeded sentinel `"0"`, and the getter's WARN hit `CrashLoggingTree` per read (22+ junk entries per session) — the DB now seeds `3` and the getter **self-heals the row once** (INFO-level, then silent). **(3) CrashLogger breadcrumb NPE on every cold start** (CRLOG-INIT-01): `breadcrumbBuffer` was declared *below* `init {}` (Kotlin declaration-order init), so `synchronized(breadcrumbBuffer)` always `monitor-enter`-NPE'd at startup and **all persisted breadcrumbs were silently dropped** — one-line declaration-order fix, pinned by tests. **(4) Release log privacy/perf** (LOG-SPAM-01): `Timber.DebugTree` was planted in release too, so full URLs/page text flooded logcat — release now gets an INFO+ `ReleaseLogTree`, and the two hottest per-event log sites are `BuildConfig.DEBUG`-gated (strings not even built in release). **(5)** predictive-back opt-in `enableOnBackInvokedCallback` added. Device-admin "thrash" in the log was investigated and is **user toggle testing, not a defect**. Carries v1.0.70 (a11y self-disable kill-vector + transparent-activity block screen + close-gate), v1.0.69 a11y persistence + dark-mode contrast, v1.0.68 block-screen reliability, v1.0.67 R8 size optimization, v1.0.66 onboarding permissions. See `docs/A11Y_ANR_PERF_AUDIT_REPORT_v1.0.71.md`. 435/435 tests pass. |
| `protect.yourself-v1.0.71-debug.apk` | ~20.3 MB | Debug | Same code with debug logging, `Protect Yourself DEBUG` label, debuggable, unminified by design. |

## Build verification (v1.0.71)

- `./gradlew :app:assembleRelease` → **BUILD SUCCESSFUL** in 5 m 25 s (R8 minified, 3,460,347 bytes; size parity with v1.0.70)
- `./gradlew :app:assembleDebug` → **BUILD SUCCESSFUL** (21,279,731 bytes)
- `./gradlew :app:testDebugUnitTest` → **435/435 tests pass, 0 failures, 0 errors, 0 skipped** (33 suites; +33 new: 8 `TraversalBudgetTest` + 7 `CountdownSelfHealTest` + 5 `CrashLoggerInitOrderTest` + 13 `A11yAnrRegressionTest`; run AFTER the builds, per release-first process)
- `apksigner verify` → **signature valid** for both APKs (debug keystore — re-sign with your own release keystore for Play distribution)
- `aapt2 dump badging` → package `protect.yourself`, versionCode `71`, versionName `1.0.71`, minSdk 26, targetSdk 35
- Merged manifest (`aapt2` inspection of the BUILT apk): `enableOnBackInvokedCallback=true` present; `SYSTEM_ALERT_WINDOW` still absent; all other permissions unchanged
- Post-R8 dex check (mapping-verified): `TraversalBudget` (`tryVisit`, `exhausted`), `ReleaseLogTree`, `lastHeavyScanByPkg` all present in the release APK (renamed consistently); a11y service manifest block intact

## New tests (33 in v1.0.71, all passing)

`features/blockerPage/service/TraversalBudgetTest` (8, pure JVM, injected clock):

| Test | What it pins |
|---|---|
| `node budget - capped exactly at maxNodes` | Hard node ceiling; failed visits don't inflate the counter |
| `time budget - exhausted when clock reaches the deadline` | 89 ms passes, 90 ms boundary exhausts |
| `remainingMillis never goes negative` | Stalled-IPC analog clamps to 0 |
| `backwards clock skew never un-exhausts the node budget` | Node cap is sticky under skew |
| `backwards clock skew extends time but never arms early` | Safe direction: lenient, never ANR-inducing |
| `zero-node budget fails the very first visit` | Degenerate config trap-guard |
| `zero-time budget fails immediately` | Degenerate config trap-guard |
| `budgets combine - node cap reached first stops before time runs out` | Either-limit-first semantics |

`features/blockScreen/CountdownSelfHealTest` (7, Robolectric + **real Room DB**):

| Test | What it pins |
|---|---|
| `stored zero - returns default AND heals the row` | The exact field case: `"0"` → returns 3, row becomes `"3"`, second read silent |
| `negative value - returns default AND heals` | Fail-safe |
| `above-max value - returns default AND heals` | 301 → default (never a longer-than-max lock) |
| `unparsable value - returns default AND heals` | `"abc"` → default + heal |
| `valid custom value - honored, row left untouched` | No write amplification for healthy rows |
| `boundary values 1 and 300 - honored` | Clamp edges unchanged |
| `absent row - returns default, row stays absent` | No heal-write when simply unset |

`features/crashLog/CrashLoggerInitOrderTest` (5, Robolectric, real logger + files dir):

| Test | What it pins |
|---|---|
| `init with pre-existing breadcrumbs file - does not throw` | The monitor-enter NPE can never return |
| `on-disk breadcrumbs are loaded into the buffer` | Previous-session breadcrumbs actually reachable |
| `appending keeps previously loaded breadcrumbs` | Ring-buffer merge semantics |
| `missing breadcrumbs file - init clean, buffer starts empty` | Fresh-install path |
| `corrupt breadcrumbs file - init never throws, buffer usable` | Crash logger never crashes the app |

`features/blockerPage/service/A11yAnrRegressionTest` (13, static source/manifest pins) — covers: depth cap ≤ 12; URL node cap ≤ 150; text node cap ≤ 150; wall-clock traversal budget ≤ 250 ms; `TraversalBudget` wired into `findUrlInNode`/`collectText` (old `nodeCounter` gone); heavy-scan throttle active in `handleContentChange`; URL/text extractors no longer re-fetch `rootInActiveWindow`; hot per-event VERBOSE logs `BuildConfig.DEBUG`-gated; DB seeds `"3"`; countdown getter heals without per-read WARN; `breadcrumbBuffer` declared before `init {}`; `ReleaseLogTree` planted behind `BuildConfig`; manifest `enableOnBackInvokedCallback="true"`.

## Manual device checklist (post-install)

1. Browse a heavy desktop-mode page in Chrome for 60 s (Porn Blocker ON) → no "App isn't responding" dialog; keyword/URL blocks still fire.
2. Release build `logcat`: no `PB-03:` / `WindowStateChange` URL/text spam (debug build still shows them).
3. Rapidly navigate A→B→C → the final page's block still triggers (throttle window closes on post-load events).
4. Fresh install → zero "Invalid block-screen countdown" crash-log entries; row seeded `3`.
5. Upgrade install with stored `0` → opening the block screen heals the row to `3`; no repeat WARN entries.
6. Kill + cold-start with an existing crash log → new entries include the previous session's breadcrumbs.
7. Regression sweep: Prevent Uninstall blocks App-Info/uninstaller/Device-Admin; a11y-settings browse keeps the service ON (v1.0.70); block screen is transparent/instant with Close toast + 3 s dwell (v1.0.70); VPN/Schedules/Focus behave as before.

## Removed APKs

All previous APK files were removed per the policy above (only the latest version is kept).
