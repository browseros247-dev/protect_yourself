# Accessibility Persistence + Dark Mode Contrast — Analysis & Fix Report (v1.0.69)

- **Version**: `1.0.69` (versionCode `69`)
- **Branch**: `fix/a11y-persist-darkmode-v1.0.69` (base: `fdf9477` = main after PR #3)
- **Scope**: two user-reported issues —
  1. *"The Accessibility Service is being disabled automatically, even though it should remain enabled."*
  2. *"Dark Mode: some settings become difficult to read — insufficient color contrast (black text on black background)."*
- **Process**: release APK built & validated **before** commit; full unit suite green before push.

---

## 1. Executive Summary

| ID | Severity | Root cause | Fix |
|---|---|---|---|
| A11Y-PERSIST-01 | **Critical** | `selfHealAccessibilityService` / `guardAllProtectedServices` performed an **unsynchronized read-modify-write** on `enabled_accessibility_services`. Racing callers (ContentObserver executor, 30 s poll, `onUnbind` scope, boot/screen-on receivers, `MainActivity.onResume`) could interleave read→append→write and produce malformed lists (`A:B:B`, duplicated entries, or last-writer-wins truncation of *other* services). Some AccessibilityManager/Settings-provider builds respond to malformed lists by **dropping our service entirely** — an actual auto-disable vector created by the self-heal itself. | Both methods are now `@Synchronized` and perform a **canonical rewrite**: component-identity-based dedupe, original order preserved, our entry normalized/appended exactly once, and the write only happens when the value would actually change (no observer-churn feedback loop). |
| A11Y-PERSIST-02 | High | `isOwnServiceEnabled` matched the stored list with **exact string equality** against one flattened form. Android/OEMs may store either the full `pkg/pkg.features.Svc` **or** the short `pkg/.features.Svc` form — a mismatch false-negatives ("service looks disabled → pointless heals + user-facing warnings") or false-negatives inside heal dedupe. | New `componentEntriesMatch(a,b)`: structural comparison via `ComponentName.unflattenFromString` (normalizes both forms), exact-equality fast path, never throws on malformed input. Used by `isOwnServiceEnabled`, self-heal dedupe, and `guardAll` union. |
| A11Y-PERSIST-03 | **Critical (primary vector)** | **Master-switch flip**: OEM/MIUI-optimization/Knox paths can set `Settings.Secure.ACCESSIBILITY_ENABLED = 0` **while our entry stays in `enabled_accessibility_services`**. Every existing check was entry-only → reported "enabled"; the guard's early-return never repaired; the ContentObserver only watched the services-list URI → never fired. **Blocking silently died while Settings still showed ON.** | New `isAccessibilityMasterEnabled()` + `isAccessibilityEffectivelyEnabled()` (entry AND master). The 30 s guard poll now uses the effective check; self-heal rewrites the master switch to `1` whenever it can write (even if the list is untouched); the ContentObserver now **also watches the master-switch URI** for instant reaction; `OnboardingPermissions` and `MyAccessibilityService.isEnabled` (home warning card) report the effective state. |
| A11Y-PERSIST-04 | Medium | `ownComponentFlat` (public lazy) eagerly dereferences `PackageManagerProvider.getPackageName()`, which throws before `App.onCreate` step 6 — any very-early caller would crash. | Added `ownComponentFlat(context)` with `context.packageName` fallback; all internal paths use the context-safe variant. Public lazy kept (pinned by `WriteSecureSettingsSetupPage` + existing tests). |
| A11Y-PERSIST-05 | Low | No transition breadcrumbs — crash logs showed *that* the service was disabled, never *when* it flipped. | Repair breadcrumb (`A11ySelfHeal: repaired: addedOwn=… deduped=… masterFixed=…`) + guard state-transition breadcrumb (`A11yGuard: effective state transition: enabled -> disabled`). Both diagnostics-only and isolated so a CrashLogger/Room failure can never flip a heal result to `false`. |
| DARK-CONTRAST-01 | **High** | M3 role pairings put **white on brand oranges**: `error`/`errorContainer` `#FF5722` + white ≈ **3.0:1**, `tertiaryContainer` `#FF9900` + onTertiaryContainer white ≈ **2.6:1**, `tertiary` `#FF7100` + white ≈ **2.8:1** (fail WCAG AA 4.5:1 for the normal-size text these roles carry); dark `outline #4D7389` on `#151F26` ≈ 3.3:1 (borderline); light subtitle `#6B7785` on `#F5F7FA` ≈ **4.25:1** (fail). Consumers: onboarding "⚠️ Important" card, BlockerPageHome status cards, Schedule pages, Backup/Restore, Crash Log, App Lock, Protected Apps, Select App — i.e. exactly "settings hard to read" in Dark Mode. | Adopted canonical **Material 3 baseline pairings** in **both** schemes: dark `error #FFB4A9`/`onError #690005` (7.7:1), `errorContainer #93000A`/`onErrorContainer #FFDAD6` (7.2:1), `tertiaryContainer #6F3A00`/`#FFDCC2` (7.1:1), `onTertiary #3B2700` (5.2:1), `outline #5D7E93` (3.9:1); light `error #BA1A1A`/white (6.5:1), `errorContainer #FFDAD6`/`#410002` (13.3:1), `tertiaryContainer #FFE0C8`/`#311300` (13.7:1), `onTertiary #311300` (6.2:1), subtitle `#64707E` (4.7:1 on `#F5F7FA`). Two buttons that used `error` as a container now pin `contentColor = onError`. All 12 text pairs × 2 schemes are now **pinned ≥ 4.5:1 by automated tests** (`ColorContrastTest`) so contrast can never silently regress. |

**Net effect**: (1) the service can no longer be left dead by the master-switch vector, list corruption created by the guard itself is impossible, and every detection path (poll, observer, home card, onboarding) reports the *real* blocking capability; (2) every text-bearing color role in both themes passes WCAG AA, verified by tests on every build.

---

## 2. Exact behavior before → after

### 2.1 Accessibility auto-disable (Issue 1)

| Scenario | Before | After |
|---|---|---|
| OEM flips `accessibility_enabled=0`, entry stays listed | Entry-only checks say "enabled"; guard early-returns; observer never fires; **blocking dead while Settings shows ON** — until the user notices and toggles manually. | Poll detects within ≤ 30 s via effective check; observer fires **instantly** (now registered on the master-switch URI too); self-heal rewrites `accessibility_enabled=1`; blocking survives. If `WRITE_SECURE_SETTINGS` is missing, the throttled 1/h notification path is unchanged. |
| Two heal callers race (observer + poll + onUnbind…) | Interleaved read→append→write could yield `A:B:B` duplicates or clobber foreign services (malformed-list drop vector). | `@Synchronized` + canonical order-preserving rewrite: exactly-once entries, foreign services preserved, write only on change. |
| Service stored in short form `pkg/.Svc` (OEM-dependent) | Exact-match said "not present" → spurious heals/warnings. | Form-tolerant structural match. |
| Very-early access to `ownComponentFlat` | Could throw (PackageManagerProvider not yet initialized). | Context-safe fallback variant used internally. |
| Healthy steady state | Poll re-asserted appends (observer churn, risk of feedback loops). | Fast path + change-only writes → zero writes when nothing is wrong. |

### 2.2 Dark Mode contrast (Issue 2) — measured ratios (WCAG 2.1 formula)

| Pair (dark) | Before | After |
|---|---|---|
| `onError` on `error` | White on `#FF5722` ≈ **3.0** ✗ | `#690005` on `#FFB4A9` ≈ **7.7** ✓ |
| `onErrorContainer` on `errorContainer` | White on `#FF5722` ≈ **3.0** ✗ | `#FFDAD6` on `#93000A` ≈ **7.2** ✓ |
| `onTertiaryContainer` on `tertiaryContainer` | White on `#FF9900` ≈ **2.6** ✗ | `#FFDCC2` on `#6F3A00` ≈ **7.1** ✓ |
| `onTertiary` on `tertiary` | White on `#FF7100` ≈ **2.8** ✗ | `#3B2700` on `#FF7100` ≈ **5.2** ✓ |
| `outline` on `surface` | `#4D7389` on `#151F26` ≈ **3.3** (borderline) | `#5D7E93` on `#151F26` ≈ **3.9** ✓ |
| `onSurfaceVariant` on `surfaceVariant` | `#B7BABD` on `#151F26` ≈ 8.6 ✓ | unchanged |

| Pair (light) | Before | After |
|---|---|---|
| `onError` on `error` | White on `#FF5722` ≈ **3.0** ✗ | White on `#BA1A1A` ≈ **6.5** ✓ |
| `onErrorContainer` on `errorContainer` | White on `#FF5722` ≈ **3.0** ✗ | `#410002` on `#FFDAD6` ≈ **13.3** ✓ |
| `onTertiaryContainer` on `tertiaryContainer` | White on `#FF9900` ≈ **2.6** ✗ | `#311300` on `#FFE0C8` ≈ **13.7** ✓ |
| `onTertiary` on `tertiary` | White on `#FF7100` ≈ **2.8** ✗ | `#311300` on `#FF7100` ≈ **6.2** ✓ |
| `onSurfaceVariant` on `surfaceVariant` | `#6B7785` on `#F5F7FA` ≈ **4.25** ✗ | `#64707E` on `#F5F7FA` ≈ **4.7** ✓ (≈ 5.1 on white cards) |

Pairs verified already-passing and left untouched: primary/onPrimary ≈ 13.2:1 both schemes, secondary/onSecondary (cyan+black) ≈ 11.5:1, onBackground/background ≈ 18.4:1, onSurface/surface ≈ 16.7:1 (dark) / 18.4:1 (light), inverse pairs ≥ 18:1, XML `values/themes.xml` + `values-night/themes.xml` (view system: correct DayNight + `light_bg`/`bg_blue_dark`), hardcoded feature colors (white on brand are tints/borders, not text-on-container pairs).

**Documented brand exception (unchanged visuals, dev decision requested):** brand-gradient buttons (`#FF7100 → #FF9900`) keep bold WHITE labels. As `labelLarge` (14sp bold) this qualifies as WCAG *large text* (3:1): the gradient start ≈ 2.76:1 and end ≈ 2.14:1 sit slightly below even that. This is a **pre-existing** brand look, out of scope for the settings-readability bug; `ColorContrastTest` pins a ≥ 2.7:1 tripwire on `#FF7100` so it can't degrade silently. Follow-up options if desired: darken label to `#3B2700`, or shift gradient end to `#E56200`.

---

## 3. Per-path scenario matrix (Issue 1)

| # | Scenario (all supported) | Detection path | Repair path | Result |
|---|---|---|---|---|
| 1 | User toggles service off in Settings | Observer (list URI) + 30 s poll | `selfHeal` → re-append + master=1 | Re-armed instantly (with permission) or throttled notification |
| 2 | OEM battery-kill removes entry | Observer/poll | Re-append canonical | Re-armed |
| 3 | **OEM flips master switch only** | Poll (effective check) + observer (master URI, new) | `putInt(accessibility_enabled,1)` — list untouched | **Fixed this round** |
| 4 | Entry + master survive, list malformed by old race | Repair path dedupes on next write | Canonical rewrite | Converges to exactly-once |
| 5 | `onUnbind` (service unbound by system) | `selfHealScope` | `selfHealSafe` (unchanged, LC-03) | Preserved |
| 6 | Boot / screen-on | `AppSystemActionReceiverAllTime` | `selfHealSafe` | Preserved |
| 7 | App foregrounded (`MainActivity.onResume`) | `selfHealSafe` | Same | Preserved |
| 8 | `WRITE_SECURE_SETTINGS` not granted | Poll detects; heal returns false | Notification (1/h throttle, BUG-24) | Preserved; notification now also correct in master-flip state |
| 9 | Process death → guards re-armed? | `ensureWatching` on every `selfHealSafe` call site | Re-register observer | Preserved |
| 10 | Very-early call before App init step 6 | — | Context-safe `ownComponentFlat(context)` | **Fixed this round** |

## 4. Per-setting review

- **Theme setting** (`ThemePreferences`, `theme_mode` 0 Light / 1 Dark / 2 System, default System): unchanged; both schemes now contrast-safe, so all three modes pass AA. `AppTheme` selection logic untouched.
- **Accessibility protection switches** (`ProtectedAppsActivity` / `ProtectedAppsRegistry` SharedPreferences): `guardAll` union now deduped + form-tolerant; registry add/remove semantics unchanged; foreign protected services never truncated (pinned by test).
- **WRITE_SECURE_SETTINGS setup** (`WriteSecureSettingsSetupPage`): unchanged; diagnostic row still uses entry-presence check intentionally (diagnoses the list entry specifically).
- **Onboarding permission row** (`OnboardingPermissions`): now reflects *effective* enabled — onboarding can no longer show "granted" while blocking is dead. Pure `buildRows` untouched → `OnboardingPermissionsTest` (14) unaffected.
- **Home accessibility status card** (`BlockerPageHome`): now driven by the effective check via `MyAccessibilityService.isEnabled` — the card can't lie in the master-flip state.
- **Backup/VPN/AppLock/Schedule/Focus/CrashLog subsystems**: behavior untouched; only their M3 role colors render with the corrected pairings (plus two `error`-container buttons now pairing `contentColor = onError`).

## 5. UI/UX, networking, stability evaluation

- **UI/UX**: dark theme now readable everywhere (status cards, onboarding warning, schedule lists, crash severity labels). Visual identity preserved: brand orange still primary accent/gradient; error color shifts from "brand orange-red" to canonical M3 light/dark error tones (standard Android look users recognize). Light theme gains a slightly darker subtitle (same hue) — imperceptible styling change, measurable contrast win.
- **Networking**: untouched. Self-heal performs only local `Settings.Secure` I/O; no new traffic, no new wakeups (observer-driven, not polling-driven, for the instant path; poll interval unchanged at 30 s).
- **Performance/stability**: change-only writes eliminate observer feedback loops and setting churn; `@Synchronized` methods are short and non-suspend (no ANR risk); breadcrumbs isolated so Room/CrashLogger hiccups can't break healing; R8 size held (~3.3 MB release); no new permissions, no manifest changes.

## 6. Testing & verification

### New tests (17)

- `features/protectedApps/AccessibilityPersistTest` — 10 Robolectric (sdk=34) cases: master-flip detection & repair (list left byte-identical), exactly-once append with foreign services preserved, malformed `A:B:B` dedupe, `guardAll` canonical union + short-form idempotence, short-form entry matching, `componentEntriesMatch` matrix (full/short/null/blank/garbage), no-clobber `selfHealSafe`.
- `theme/ColorContrastTest` — 7 pure-JVM cases: WCAG 2.1 luminance math asserting **all 12 text pairs ≥ 4.5:1 in BOTH schemes**, outline ≥ 3.0:1 on surface, exact hex pins for the corrected roles, and the brand-exception tripwire (≥ 2.7:1 on `#FF7100`).

### Full suite + builds (release built & validated BEFORE commit, per process)

- `:app:assembleRelease` → **BUILD SUCCESSFUL** (R8; ~3.3 MB held)
- `:app:assembleDebug` → **BUILD SUCCESSFUL** (~20.3 MB)
- `:app:testDebugUnitTest` → **375/375 pass, 0 failures, 0 errors, 0 skipped** (26 suites; 358 prior + 17 new)
- `apksigner verify` both APKs → signature valid
- `aapt2 dump badging` (release) → `protect.yourself`, versionCode `69`, versionName `1.0.69`, minSdk 26 / targetSdk 35
- Post-R8 mapping/dex check → all new methods present (renamed consistently), settings keys + breadcrumb strings in DEX, a11y service manifest block intact (`exported=true`, `BIND_ACCESSIBILITY_SERVICE`, `@xml/accessibility_setting`)

### Manual on-device checklist (no emulator in sandbox)

1. Enable accessibility → `adb shell settings put secure accessibility_enabled 0` → within ≤ 30 s, `settings get` shows `1` again and blocking keeps working (A11Y-PERSIST-03).
2. With `WRITE_SECURE_SETTINGS` granted: toggle service off in Settings → instantly re-enabled (observer path).
3. Without the permission: disable service → one "re-enable" notification per hour max (BUG-24 throttle intact).
4. Dark Mode: walk Blocker home (both status cards), App Lock setup ("Disable App Lock" button readable), Schedule editor, Backup & Restore destructive dialog, Crash Log severity labels, onboarding "⚠️ Important" card — all clearly readable.
5. Light Mode + System Mode: same screens readable; subtitle text on cards clearly legible.

## 7. Regression watchlist (verified unchanged)

- Onboarding two-step flow + permission intents (`OnboardingPermissionsTest` 14 ✓ — only the accessibility row's *state source* changed).
- Block-screen v1.0.68 behavior (`BlockScreenReliabilityTest` 10 ✓; `BlockOverlayManager`, `PornBlockActivity` untouched).
- R8 keep rules (`ProguardRulesRegressionTest` 8 ✓; no new reflection-created classes → no new keep rules needed; mapping confirms new code present).
- Service lifecycle LC-01..03 (`MyAccessibilityServiceLifecycleTest` ✓ — incl. permission-missing early-return false and `ownComponentFlat` public lazy).
- Notification throttle prefs (`accessibility_guard_prefs`, `last_disabled_notif_ms`) untouched.
- No manifest/permission/dependency changes; min/target SDK unchanged.
