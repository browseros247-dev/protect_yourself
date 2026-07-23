# Proactive Audit Round Report — v1.0.73 (versionCode 73): CrashLogger thread-safety + per-WARN binder IPCs

**Date:** 2026-07-23 · **Branch:** `fix/crashlog-threadsafety-ipc-v1.0.73` · **Base:** `main` @ `054878f0` (PR #7, v1.0.72)
**Scope (user-approved):** proactive general bug-hunt round per the standing objective — *"identify and fix all errors, bugs, and issues while preserving the current functionality. Every fix must be thoroughly verified to ensure that it does not introduce any new bugs, regressions, performance issues, stability problems, or unintended side effects."*
**Process honored:** deep RCA first → minimal behavior-preserving fixes → **release APK built & verified FIRST** → full test suite → docs/APK rotation → two commits → branch push (user merges via GitHub UI).

## Executive summary

| ID | Area | Root cause | Fix | Tests |
|---|---|---|---|---|
| CRLOG-TS-01 | CrashLogger concurrency | Two shared `SimpleDateFormat` fields formatted **without any lock** while `CrashLoggingTree` routes every WARN+ Timber call into `CrashLogger` from ANY thread. `SimpleDateFormat.format()` mutates an internal `Calendar` — concurrent calls race on that state and can emit corrupted timestamp strings or throw internal `ArrayIndexOutOfBoundsException`. Either failure poisons the crash records themselves (substring garbage in JSON) or drops entries via the re-entrancy guard when a throw escapes mid-`logThrowable`. | One `timestampLock`; every format call (logThrowable, logMessage, logBreadcrumb, export, id-generation) funnelled through `formatTimestamp(date)` / `formatFileTimestamp(date)` wrappers holding the lock. | `CrashLoggerRobustnessTest` — 1 static pin + 2 functional concurrency smoke tests |
| CRLOG-IPC-01 | CrashLogger per-log syscall cost | Every WARN+ entry built `AppInfo` via `getCurrentProcessName()` + `getInstallerPackageName()` (2 binder IPCs) and `ServiceStateInfo` via a `Settings.Secure` read + `DevicePolicyManager.isAdminActive()` (~2 IPCs) — **3–4 synchronous binder calls on the caller thread per WARN log**. During any warning storm (the app has hit two in the field: countdown-WARN loop 1.0.70, a11y NPE loop 1.0.71) this is exactly the per-event binder-storm class behind the 22 vivo V2206 ANRs. | `cachedAppInfo` (AppInfo is process-immutable → lazy one-shot) + `captureServiceStateCached()` with the same 1 s TTL pattern already used for memory/disk. Entry JSON content/fields unchanged — values merely ≤1 s cached instead of fresh-per-call. | `CrashLoggerRobustnessTest` — 2 static pins; all 5 `CrashLoggerInitOrderTest` + 5 existing crash-log suites still green |

**Result:** 458/458 unit tests green (37 suites, +5 new vs 453). Release APK 3,274,757 B — byte-size stable vs v1.0.72 — signed, versionCode 73 / 1.0.73 verified, R8 mapping confirms the new code paths.

## Audit methodology & negative results (what was checked and proven SAFE)

Round swept these subsystem classes, each with a concrete suspicion and a read pass (no changes needed — documented so future rounds don't re-burn the time):

| Sweep | Suspicion | Result |
|---|---|---|
| `!!` force-unwraps (5 sites) | NPE risk | All safe: `BackupRestorePage.pendingImportUri!!` is state-machine-guarded (dialog only opens when non-null, cancelled clears); `KeywordMatcher` failure-chain `!!`s are loop-invariant (parent non-null ⇒ tested by 43 `BlockingValidatorTest` cases) |
| `GlobalScope` leaks | Unstructured concurrency | Only appears in a comment in `AppContainer`; all scopes are lifecycle/service/app-managed (`viewModelScope`, `serviceScope`, named `appCoroutineScope` helpers) |
| `runBlocking` in `MyVpnService` (3 sites) | ANR/deadlock | Deliberate, documented, on binder threads (not main): persists `VPN_SWITCH=false` before `stopSelf()` races scope cancellation (BUG-02/BOOT-VPN-03 lineage) |
| `BlockerPageViewModel` (1788 lines) | Unguarded coroutines / Handler leaks | Single `init` + one `viewModelScope.launch`; every mutating call routes through `safeLaunch` (try/catch + user toast); zero `Handler`/`Timer` leaks |
| `BackupManager` (904 lines, 0 prior tests) | Data-loss on restore | Already hardened: transactional `withTransaction` restore (rollback on any failure), version gates, null-table guard, `wt`→`w` write-mode fallback, post-write size verification (metadata → byte-count fallback), row sanitization skipping blank PKs, OOM/IO/Security exception mapping, CancellationException propagation |
| Manifest receivers (8) | Missing `package:` data-scheme / export mistakes | Correct: `PACKAGE_ADDED/REMOVED` in their own filter WITH `<data android:scheme="package"/>`; `MY_PACKAGE_REPLACED` in a separate filter without it (exactly the classic trap, already documented & handled); non-exported alarm receiver; device-admin guarded by `BIND_DEVICE_ADMIN` |
| DB/singleton init | DCL races | `@Volatile` + double-checked locking on `AppDatabase`, `CrashLogger`, `AppLockManager`; `OncePerSessionLogger` uses `ConcurrentHashMap.putIfAbsent` (atomic check-then-act) |
| Accessibility event pipeline | Post-v1.0.71 regressions | Kill-vector guard (A11Y-KILL-01), event-type coverage fixes (IAB-02/PU-02/SET-03), traversal budgets and shared root fetch intact; whole outer block try/caught + crash-logged |
| StopMe / widgets | Scope leaks, hardcoded values | Named `appCoroutineScope("StopMeWidget")`, `goAsync`+`finally { finish() }`, duration from last session (PM-07), main-Looper Toast |

**Conclusion:** seven rounds of hardening show; only CrashLogger yielded actionable defects.

## CRLOG-TS-01 — detailed RCA

- **Entry path:** `CrashLoggingTree.log(priority ≥ WARN)` → `crashLogger.logThrowable / logMessage` — called from *whatever thread logged*: accessibility binder thread (a11y WARNs), WorkManager worker threads, `Dispatchers.IO` coroutines, main.
- **Race:** `dateFormat`/`fileDateFormat` are shared mutable formatters used at 5 sites, none inside the existing `synchronized(this)`/`synchronized(breadcrumbBuffer)` sections (those guard persistence and the ring buffer only). Two concurrent WARNs (realistic: a11y event WARN + worker WARN within the same ms) interleave `Calendar` mutations → wrong/garbage date fields or internal AIOOBE.
- **Why it matters here:** corrupted `timestampFormatted` strings land INSIDE the JSON crash records (the forensic data); a throw inside `logThrowable` hits the crash-handler re-entrancy path and the record is LOST — both outcomes degrade the exact tool used to diagnose field crashes, and failures inside fault storms (when concurrency is maximal) are the likeliest.
- **Fix:** single private `timestampLock`; wrappers `formatTimestamp`/`formatFileTimestamp`; all 5 call sites rewired. No API, format, or output change for single-threaded callers.

## CRLOG-IPC-01 — detailed RCA

- **Cost accounting per WARN+ entry (before):** `getCurrentProcessName` (1 IPC) + `getInstallerPackageName` (1 IPC) + `Settings.Secure.getString` (provider IPC) + `DevicePolicyManager.isAdminActive` (1 IPC) ≈ 3–4 binder transactions (~2–40 ms combined worst case) — synchronously on the logging thread.
- **Contrast:** memory/disk info were ALREADY 1-second-TTL cached for exactly this reason (comment: *"avoids hammering ActivityManager on every WARN+ Timber log"*); AppInfo/ServiceState were missed.
- **Fix matches the established pattern:** AppInfo immutable per process (package name, SDK levels, installer, process name cannot change at runtime) → `by lazy` one-shot. Service state drifts slowly and is diagnostic, not transactional → 1 s TTL (`SERVICE_STATE_CACHE_TTL_MS`), constants grouped with the existing TTLs.
- **Functionality preserved:** entry fields and semantics identical; dedup, pruning, export, OOM fallback untouched; worst-case staleness 1 s for the diagnostic service flags (same as memory/disk, already accepted).

## Build & verification (release-first)

1. `compileDebugKotlin` + `compileDebugUnitTestKotlin` → BUILD SUCCESSFUL (after fixing one test-authoring slip: Truth `StringSubject` has no `isNotBlank()` → `isNotEmpty()`).
2. **`assembleRelease` FIRST** → BUILD SUCCESSFUL in 5 m 46 s → **3,274,757 B** (size parity with v1.0.72).
   - `apksigner verify --print-certs` → valid (debug keystore per repo policy).
   - `aapt2 dump badging` → `protect.yourself`, **versionCode 73 / 1.0.73**, minSdk 26, targetSdk 35.
   - R8 mapping → `cachedAppInfo$delegate`, `captureServiceStateCached()` present — new code paths live in the release dex.
3. `assembleDebug` → BUILD SUCCESSFUL (21,296,115 B).
4. `testDebugUnitTest` → **458/458 pass, 0 failures/errors/skipped, 37 suites** (453 + 5 new).
   - One intermediate failure during the round: my own new smoke test's JSON regex assumed a space after `:` that Gson doesn't emit → regex corrected to `:\\s*`; production code untouched. Suite re-run green.

## Regression watchlist

| Risk | Mitigation / evidence |
|---|---|
| Lock contention from `timestampLock` | Lock scope = one `SimpleDateFormat.format` (~µs); cheaper than the per-entry file write it precedes; far below the binder-IPC cost REMOVED by IPC-01 |
| `cachedAppInfo` staleness | AppInfo fields (version/package/SDK/installer/process) cannot change without process death; CrashLogger is process-scoped |
| Service-state TTL masking a transition user cares about | Diagnostic metadata only, identical staleness class as existing memory/disk TTL; capture-on-FATAL still includes fresh logcat tail |
| Reflection-based singleton reset in tests | Same pattern as `CrashLoggerInitOrderTest` (Kotlin companion static field), proven stable across rounds |
| Flaky concurrency smoke tests | Assertions are invariant-based (no exceptions escaped, ids returned, well-formed persisted output) not schedule-based; 90 s gates, daemon workers |

**All previously-existing suites green unchanged** (453 baseline): CrashLoggerInitOrderTest (5), A11yAnrRegressionTest (13), TraversalBudgetTest (8), CountdownSelfHealTest (7), ColorContrastTest (10), DarkModeButtonsTest (4), ThemeSwitcherPlacementTest (4), OnboardingEnforcementTest (7), OnboardingPermissionsTest (14), BlockingValidatorTest (43), etc.
