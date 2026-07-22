# Field Crash-Log Audit — Accessibility ANRs, WARN Spam, Breadcrumb NPE — Analysis & Fix Report (v1.0.71)

- **Version**: 1.0.71 (versionCode 71)
- **Branch**: `fix/a11y-anr-perf-v1.0.71`
- **Base**: `origin/main` @ `5c6ea92` (post-PR #5, v1.0.70)
- **Input**: user-supplied crash export `protect_yourself_crashlogs_1784731643352.md` — vivo V2206, Android 14 (SDK 34), release build **v1.0.69**, 49 entries over ~90 min
- **Date**: 2026-07-22

---

## 1. Executive Summary

| ID | Symptom (field data) | Root cause | Fix | Verified by |
|---|---|---|---|---|
| A11Y-ANR-01 | **22 FATAL ANRs**, main thread blocked 5,118–11,761 ms. Two stack families: 15× recursive `MyAccessibilityService.d()` (25–30+ frames) each frame = one `AccessibilityNodeInfo.getChild()` binder IPC (`TIMED_WAITING` in `waitForResultTimedLocked`); 7× blocked even fetching `getRootInActiveWindow`. Plus `Choreographer: Skipped 244 frames`. | Tree walks ran per accessibility event (5–20/s render bursts in Chrome) with **node-count-only guards** (300/500 nodes) assuming "~2 ms per IPC". Under real contention a single binder round-trip takes 10–50 ms → 300–500-node walks = 5–15 s. The root window was fetched **twice per event** (URL pass + text pass). | (a) New `TraversalBudget` — **hard time (90 ms) + node cap**, injected clock, checked before every per-node IPC; (b) caps lowered: depth 50 → 12, URL nodes 300 → 150, text nodes 500 → 150; (c) per-package **heavy-scan throttle** (350 ms) collapses render bursts to one scan while settled destination URLs are still caught by post-load events; (d) root fetched **once per scan** and shared between URL/text passes; (e) unit pins. | `TraversalBudgetTest` (8) + `A11yAnrRegressionTest` (13) + mapping-verified release build |
| SET-COUNTDOWN-01 | "Invalid block-screen countdown stored (0) — using default 3s" **persisted as a crash entry on every read** (logcat shows 22+ `Crash entry persisted … (WARN/untagged)` in one session; each `BlockerPage loaded` reload triggered 1–2). | `AppDatabaseCallback` **seeded `block_screen_count_down_time_set = "0"`**; the getter's invalid-value branch used `Timber.w`, and `CrashLoggingTree` persists every WARN → per-read crash entries. | Seed `"3"` for fresh installs; getter now **self-heals the row once** (`dao.upsert` default, INFO-level) and stays WARN-free on the happy path; heal-write failure path routes through `OncePerSessionLogger` (≤1 WARN/session). Invalid → default semantics (TIMER-DEFAULT-01) unchanged. | `CountdownSelfHealTest` (7, real Room DB) + static pins |
| CRLOG-INIT-01 | `CrashLogger: Failed to load breadcrumbs from disk — NullPointerException: Null reference used for synchronization (monitor-enter)` at **every cold start** (20:45 restart proof: entries afterwards show only in-session breadcrumbs). | `breadcrumbBuffer` was declared **below** `init {}` in `CrashLogger`. Kotlin initializes in declaration order, so `init → loadBreadcrumbsFromDisk() → synchronized(breadcrumbBuffer)` hit a null buffer → NPE (caught by the load's own catch) → **all persisted breadcrumbs silently dropped** at startup. | Declaration moved **above** `init`; comment left at the old spot; corrupt-file path still degrades safely. | `CrashLoggerInitOrderTest` (5, real logger + real files dir) + declaration-order static pin |
| LOG-SPAM-01 | Release logcat full of VERBOSE a11y logs: `PB-03: skipping duplicate url=…` (thousands, incl. **full browsing URLs**), `WindowStateChange … text=<page text>` — privacy leak + main-thread string building on the hot path. | `Timber.DebugTree()` planted in **all** builds; hot call sites always built the interpolated strings. | Release builds now plant `ReleaseLogTree` (INFO+; WARN/ERROR stack traces kept); the two hottest sites are additionally `if (BuildConfig.DEBUG)`-gated so strings aren't even built in release. CrashLoggingTree unchanged (WARN+ still persisted). | `A11yAnrRegressionTest` pins + release APK mapping |
| MANIFEST-01 | `WindowOnBackDispatcher: OnBackInvokedCallback is not enabled…` warning at every activity creation | Missing predictive-back opt-in | `android:enableOnBackInvokedCallback="true"` (verified the app uses `OnBackPressedDispatcher`, not deprecated overrides) | manifest pin + APK xmltree dump |
| DA-THRASH (investigated, **not a defect**) | "Device admin enabled" → "Prevent uninstall enabled" → app-initiated `removeActive` — 4 cycles / 53 s | The only `removeActive` caller is the **user's** Prevent-Uninstall OFF toggle (`BlockerPageViewModel`, UI event). Timeline matches manual toggle testing interleaved with PU block screens. Receiver + ViewModel behaved exactly as designed. | No code change (documented). | code-path audit (`git grep removeActive`) |
| PU-a11y-page blocks in log (already fixed in v1.0.70) | `PU: blocking accessibility settings page for our service` + overlay fallback breadcrumbs | A11Y-KILL-01 / ACTIVITY-BLOCK-01 — **fixed & merged in PR #5** (this export is from v1.0.69) | — | v1.0.70 round |

**Result:** 435/435 automated tests pass (+33 new), release APK built, signed and verified **before** commit per process. All blocking functionality preserved: URL scraping order (SafeSearch first), content-text keyword matching, PU/anti-uninstall checks, a11y-management-screen protection, v1.0.68–1.0.70 fixes (all 402 pre-existing tests still green).

---

## 2. Timeline reconstruction (what the export shows)

```
19:18        install → onboarding → a11y service granted → BOTH ANR families begin accumulating
19:19–19:21  user tests Prevent Uninstall (admin ON/OFF ×4) — works as designed
19:20:19–20  PU (correctly for v1.0.69 semantics, wrongly for a11y safety) blocks our own a11y
             settings page — the EXACT A11Y-KILL-01 bug fixed in v1.0.70 (PR #5)
19:23–20:20  13 more FATAL ANRs while browsing Chrome (github/huggingface pages) —
             recursive getChild chains (d×30) & rootInActiveWindow binder stalls
20:45:47     process restart → CrashLogger breadcrumb-load NPE → breadcrumbs lost
19:18–20:47  22× "Invalid block-screen countdown stored (0)" persisted as crash entries
```

## 3. Root-cause details & fixes

### 3.1 A11Y-ANR-01 — main-thread binder IPC storms (22 FATAL)

**Why node-count guards failed:** `getChild()` is a synchronous binder call **into the remote app process**; latency depends on system load, not on our code. The `ANR-01 (v1.0.58)` fix capped *counts* (300 URL / 500 text nodes) on the "~2 ms" theory — the field device needed 10–50 ms per call under load, so worst-case walks were 5–15 s. Two more amplifiers:

1. **Event frequency**: `TYPE_WINDOW_CONTENT_CHANGED` fires 5–20×/s while Chrome renders; every event did a root fetch + full walk.
2. **Double root fetch**: `extractUrlFromEvent` and `extractTextFromEvent` each called `getRootInActiveWindow` per event — 7 ANRs stalled *in that fetch itself*.

**Fix (defense in depth):**

| Layer | Before | After |
|---|---|---|
| Per-node guard | node counter (300 / 500) | `TraversalBudget(maxNodes, maxMillis=90ms)` — `tryVisit()` before every IPC; **time is the deterministic ceiling the node count can't provide** |
| Depth cap | 50 | 12 (address bar is shallow; kills 30+-frame recursion) |
| URL nodes | 300 | 150 |
| Text nodes | 500 | 150 (keyword matching works on partial text) |
| Scan frequency | every content event | **350 ms per-package throttle** (`lastHeavyScanByPkg`) — render bursts collapse to one scan; settled URL still caught by later events; PB-03 dedup unchanged |
| Root fetch | 2× per event | **1× per scan**, shared by URL + text passes |
| Budget exhaustion | (impossible to detect) | logged (INFO breadcrumb path) — never an ANR again |

Exhaustion returns **partial results** — URL scrape is unaffected in practice (address-bar view-ID strategy runs first; fallback search covers 150 top nodes), and content-text matching only needs enough text to keyword-match.

**Preserved behavior:** SafeSearch-first ordering, URL-first-then-text priority, stale-event guard, IAB checks, PU checks, a11y-management-screen early return — all untouched.

### 3.2 SET-COUNTDOWN-01 — WARN-per-read crash entries

- `AppDatabaseCallback` seeded the countdown row `"0"` → getter treated it as invalid → `Timber.w` on **every read** → `CrashLoggingTree` persisted each one as a crash entry → export noise (22+ in a session) and real signal buried.
- Fix: seed `"3"` (fresh installs); on an invalid stored value the getter **heals the row to the default once** (INFO), so the repair is zero-noise afterwards; if the heal write itself fails, `OncePerSessionLogger` caps the WARN at one per session. A truly absent row stays absent (no write) — unset already means "default".
- TIMER-DEFAULT-01 semantics (invalid/missing → 3 s, fail-safe 1..300 clamp) are unchanged and pinned by the existing 10 `BlockScreenReliabilityTest` cases plus 7 new heal tests.

### 3.3 CRLOG-INIT-01 — breadcrumb NPE on cold start

- `init {}` runs property initializers in **declaration order**. `loadBreadcrumbsFromDisk()` (called from init) synchronizes on `breadcrumbBuffer`, which was declared ~760 lines further down → field null → `monitor-enter` NPE → caught → breadcrumbs dropped *every cold start*.
- Fix: single-line declaration moved above `init` (+ KDoc + static pin asserting the order can never regress). Corrupt JSON still degrades to an empty usable buffer (tested).

### 3.4 LOG-SPAM-01 — release logging hygiene

- New `ReleaseLogTree` (INFO+, WARN+ stack traces) planted in release builds; `DebugTree` debug-only.
- The two hottest per-event templates (`WindowStateChange …text=`, `PB-03 skip duplicate`) are `BuildConfig.DEBUG`-guarded at the call site, so the strings are **not even constructed** in release.
- INFO+ operational logs (config refresh, block decisions, ScheduleEngine, workers) remain → CrashLogger `logcatTail` on FATLs stays diagnostic.

### 3.5 MANIFEST-01 — predictive back

`enableOnBackInvokedCallback="true"` added; verified against the codebase (`OnBackPressedDispatcher` everywhere, the one deprecated override just delegates to the dispatcher).

## 4. Per-setting review (standing instruction)

| Setting / subsystem | Reviewed | Change |
|---|---|---|
| Porn Blocker + SafeSearch URL scraping | Hot path of A11Y-ANR-01 | Throttled + budgeted; ordering & semantics unchanged |
| Content-text keyword block (PB-02) | Second hot path | Budgeted, shared root; gate unchanged |
| Block-screen countdown | SET-COUNTDOWN-01 | Seed + heal-once; value semantics unchanged |
| Block-screen redirect/custom message/image | Same DAO family | Untouched |
| Prevent Uninstall (App-Info/uninstaller/DeviceAdmin) | DA-THRASH investigated | **No change** — user-toggle path only; behavior correct |
| Accessibility-management protection (v1.0.70) | Interacts with event flow | Untouched; `AccessibilityPersistTest` (10) green |
| Self-heal on a11y screens (v1.0.70) | Untouched | green |
| Block overlay → transparent activity (v1.0.70) | Rendering path unaffected | `OverlayDependencyRemovedTest` (9) green |
| Close-gate (v1.0.70) | Reads the healed countdown | `CloseGatePolicyTest` (7) green |
| CrashLogger / CrashLoggingTree | CRLOG-INIT-01 + counts | Init order fix; WARN→entry policy unchanged (only the spam sources were removed at the source) |
| VPN/DNS, Schedules, Focus, Backup, Daily report | Evaluated for interaction | Untouched (logs show all workers SUCCESS) |

## 5. Testing & verification

### New tests (33, all passing)

| Suite | Count | Pins |
|---|---|---|
| `features/blockerPage/service/TraversalBudgetTest` | 8 | node cap exactness; time deadline; boundary at deadline; negative-remaining clamp; backwards-skew safety (node cap sticky, time never arms early); zero-node/zero-time budgets |
| `features/blockScreen/CountdownSelfHealTest` (Robolectric, real Room DB) | 7 | stored `0`/negative/above-max/unparsable → default + row healed to `3`; valid 7 honored untouched; boundaries 1/300; absent row → default with **no** write |
| `features/crashLog/CrashLoggerInitOrderTest` (Robolectric) | 5 | init with pre-existing file doesn't throw; disk breadcrumbs actually loaded; append keeps loaded ones; missing file → clean empty buffer; **corrupt file** → no throw, buffer usable |
| `features/blockerPage/service/A11yAnrRegressionTest` (static pins) | 13 | depth ≤ 12; URL/text node caps ≤ 150; time budget ≤ 250 ms; TraversalBudget in both walkers; heavy-scan throttle wired; extractors don't re-fetch root; DEBUG-gated hot logs; seed `"3"`; getter heal without per-read WARN; CrashLogger declaration order; ReleaseLogTree planted behind BuildConfig; manifest back-callback flag |

### Full suite + builds (release built & validated BEFORE commit, per process)

| Gate | Result |
|---|---|
| `:app:assembleRelease` (R8) | **BUILD SUCCESSFUL** in 5 m 25 s → 3,460,347 B (~3.46 MB, parity with v1.0.70) |
| `apksigner verify` | **signature valid** (debug keystore — re-sign for distribution) |
| `aapt2 dump badging` | `protect.yourself`, **versionCode 71, versionName 1.0.71**, minSdk 26, targetSdk 35 |
| Merged manifest | `enableOnBackInvokedCallback=true` confirmed in binary XML; `SYSTEM_ALERT_WINDOW` still absent |
| Post-R8 mapping | `TraversalBudget` (`tryVisit`), `ReleaseLogTree`, `lastHeavyScanByPkg` present & consistently renamed |
| `:app:assembleDebug` | **BUILD SUCCESSFUL** (21,279,731 B) |
| `:app:testDebugUnitTest` (re-run after builds) | **435/435 pass, 0 failures, 0 errors, 0 skipped** (33 suites; 402 prior all still green) |

### Manual device checklist (v1.0.71)

1. Browse a heavy page in Chrome (long GitHub README) for 60 s with Porn Blocker ON → no "App isn't responding"; `adb shell dumpsys activity lru` shows no ANR record for `protect.yourself`; block still fires on matching keywords/URLs.
2. `logcat --pid=$(pidof protect.yourself)`: no `PB-03:`/`WindowStateChange` VERBOSE lines in the **release** build (debug build shows them).
3. Rapid navigation A→B→C: destination C's block still triggers (post-load events exceed the 350 ms throttle window).
4. Fresh install → Blocker page opens with **zero** "Invalid block-screen countdown" entries; DB row `block_screen_count_down_time_set = 3`.
5. Existing install (row = 0): open block screen once → row healed to 3, no further WARN entries in Crash Log.
6. Kill & cold-start the app with an existing Crash Log → new crash entries contain the **previous session's** breadcrumbs (DeviceAdmin/A11yGuard/BlockAttempt history).
7. Regression spot-checks: PU on → App-Info/uninstaller still blocked; a11y settings browse → service stays ON (v1.0.70); Close gate toast + 3 s dwell (v1.0.70); block screen transparent, instant.

## 6. Regression watchlist (all green)

`BlockScreenReliabilityTest` (10) · `AccessibilityPersistTest` (10) · `ProtectedSystemScreensTest` (11) · `CloseGatePolicyTest` (7) · `OverlayDependencyRemovedTest` (9) · `MyAccessibilityServiceTest/LifecycleTest` · `ProguardRulesRegressionTest` (8) · `OnboardingPermissionsTest` (14) · `ColorContrastTest` (7) — **402 prior tests unaffected and passing**; +33 new = 435 total.
