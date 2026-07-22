# UI / Theme / Onboarding / Dark-Mode Round Report — v1.0.72 (versionCode 72)

**Date:** 2026-07-22 · **Branch:** `fix/ui-theme-onboarding-darkmode-v1.0.72` · **Base:** `main` @ `2570808` (PR #6, v1.0.71)
**Scope (user-requested, 4 tasks):** (1) Profile Theme card → compact top-bar icon+d dropdown; (2) onboarding consistency — required settings mandatory; (3) Dark-Mode button visibility, WCAG-compliant buttons app-wide; (4) comprehensive layout/alignment audit, esp. onboarding + Reliable-Accessibility cards.
**Constraint honored:** preserve all current functionality; no new bugs, regressions, perf/stability side effects. Release APK built and verified **first**, then full tests, then commit/push.

## Executive summary

| ID | Area | Root cause | Fix | Tests |
|---|---|---|---|---|
| THEME-SWITCH-01 | Theme selection | Bulky 3-radio card consumed a Profile-tab slot; user wants quick top-level switching | New `theme/ThemeSwitcher.kt`: `ThemeSwitcherIcon()` (sun/moon by effective brightness) in a new `TopAppBar` on `MainScreen`; `DropdownMenu` with System Default / Dark / Light, leading icons + check on current mode; old card + `ThemeOptionRow` + dead imports removed from `ProfilePage` | `ThemeSwitcherPlacementTest` (4) |
| OB-ENFORCE-01 | Onboarding | Permissions step was "skippable by design" (v1.0.66); users could enter the app with zero protection prerequisites; launch gate only checked terms+lock | `Continue to App` now `enabled = allRequiredGranted(rows)` (REQUIRED = accessibility + notifications-33+; RECOMMENDED stay optional); `checkAppState()` re-evaluates required permissions at entry and re-routes to ONBOARDING starting directly at the checklist; entry-only enforcement; evaluator-crash fail-open + crash log | `OnboardingEnforcementTest` (7) |
| DARK-BTN-01 | Dark-Mode buttons | Dark `primary` = near-black navy #1F323F used as CONTENT color by every TextButton/OutlinedButton label+border → ≈1.3:1 on dark surfaces (invisible Allow/Disallow/Export/Import/Open/Close/Cancel); filled brand buttons BrandOrange+white ≈2.8:1 (below 3:1 large-text floor) | Dark scheme `primary`/`onPrimary` → #9DB8C6 / #0A2029 (≈8:1 both ways; navy survives as `primaryContainer` + as light primary); all 12 filled-brand-button sites + 2 keypad sets + FAB → `brandButtonColors()` = #B85700 + white ≈4.77:1 (AA) | `DarkModeButtonsTest` (4) + `ColorContrastTest` (+3, suite 10) |
| UI-CONSIST-01 | Layout audit | Onboarding status tag glued to title wrapped unpredictably; banner variants mismatched (20/24dp icons, 8/12dp gaps); Reliable-Accessibility status circles white-on-#4CAF50/#FF9800 ≈2.5/2.2:1; diagnostics component-name clipped; Profile "View" label ≈2.8:1 on light | Status tag on own line; banner icon/spacer parity; `StatusSuccess`#2E7D32 / `StatusWarning`#E65100 tokens (≈4.9/3.6:1 with white); StepCard circle pairs with `onPrimary`; DiagnosticsRow value wraps (weight+end+ellipsis); chevron affordance | covered by static pins + contrast suite |

**Result:** 453/453 unit tests green (36 suites, +18 new vs 435 baseline); release APK 3,274,757 B signed + manifest-verified; no behavior changes outside the four requested areas.

---

## 1) THEME-SWITCH-01 — top-bar theme switcher

**Before (ProfilePage):** a full-width "Theme" card with icon + 3 `ThemeOptionRow` radio rows inside the Profile LazyColumn; state via `ThemePreferences.themeMode.collectAsState()`; write via `ThemePreferences.setThemeMode`.

**After:** `MainScreen`'s `Scaffold` gains a `topBar` — `TopAppBar(title = "Protect Yourself" (onBackground), actions = { ThemeSwitcherIcon() })` on `background`. `ThemeSwitcherIcon()`:
- trigger `IconButton`: sun in effective-light, moon in effective-dark (same LIGHT/DARK/system mapping as `AppTheme`), tint = `colorScheme.primary` (now a high-contrast content color in both schemes — see DARK-BTN-01), `contentDescription = "Theme: <mode>. Tap to change."`
- `DropdownMenu` with 3 items in the requested order — **System Default** (`PhoneAndroid`), **Dark** (`DarkMode`), **Light** (`LightMode`) — leading icon (primary when selected), bold label when selected, trailing check on the current mode; each `onClick` → `ThemePreferences.setThemeMode(...)` → dismiss.
- Persistence + instant apply are identical to the old card (same SharedPreferences + StateFlow collected by `AppTheme`).

**ProfilePage cleanup:** theme card item removed; `ThemeOptionRow` composable removed; `currentThemeMode` state removed; dead imports removed (`DarkMode`, `LightMode`, `RadioButton`, `ThemePreferences`); `collectAsState` retained (still used by crash-entry count).

**Risk review:** TopAppBar is stable in material3 1.3.x (BOM 2024.10.01) — no experimental opt-in. Icons from `material-icons-extended` (already a dependency). The top bar shifts tab content down by its height on all three tabs — standard Scaffold behavior, verified visually via paddings already applied by each tab composable. No callbacks/navigation identity changes, so no state-loss surfaces.

## 2) OB-ENFORCE-01 — required onboarding settings are mandatory

**Audit findings (consistency):**
1. Terms step: correctly gated (checkbox → enabled button). ✓ kept.
2. Permissions step: **skippable by design** — "Continue to App" always enabled + passive warning text + `finish_with_missing` breadcrumb. ✗
3. Launch gate: `checkAppState()` checked only terms → lock → MAIN. A user who accepted terms but skipped onboarding once (e.g. pre-1.0.66 installs, or process death on the checkerboard) reached MAIN forever with zero required grants. ✗

**Fix (two enforcement layers):**

- **Step-level gate:** `val requiredReady = OnboardingPermissions.allRequiredGranted(rows)`; Continue `enabled = requiredReady`. Guidance text replaced: old "You can continue without them…" → "Grant the Required permissions above to continue — protective features cannot work without them." Rows re-evaluate on action results and ON_RESUME (existing mechanism), so the button live-enables when the last required grant lands. Header copy updated to state the requirement. Finish breadcrumb: `finish_all_granted` or `finish_required_complete(recommended_missing=[...])` — **RECOMMENDED (battery exemption, exact alarms) deliberately stay optional** (degraded reliability is recoverable; missing accessibility/notifications silently break core features, which is exactly the "mandatory" class).
- **Entry-level gate:** `checkAppState()` now computes `requiredPermissionsReady` (via the existing pure evaluator) whenever terms are accepted, and routes `!terms || !requiredReady → ONBOARDING`, setting `onboardingStartAtPermissions = termsAccepted && !requiredReady` so re-entry lands **directly on the permissions checklist** (terms step skipped — its DB record already exists). `OnboardingPage` gained `startAtPermissions`; the PERMISSIONS-step BackHandler only returns to TERMS when the user actually came from TERMS this session.
- **Fail-open on catastrophe:** the evaluator is fail-closed per permission (broken OEM read ⇒ "not granted" ⇒ blocked) — never a false pass. But a top-level evaluator *crash* is caught, crash-logged (`"required-permission gate failed open"`), and treated as ready: locking a user out of the whole app on an OEM platform bug would be worse than degraded protection.
- **Entry-only enforcement:** `onResume` re-check calls `checkAppState(enforceRequiredPermissions = false)`. Rationale: mid-session revocation is surfaced by the existing in-app warning banner + `selfHealSafe` re-arm (v1.0.69/70), and bouncing a user out of MAIN on a transient state would be a regression-class UX break. Next cold start re-gates. This is a deliberate, documented consistency choice: mandatory at the door, self-healing inside.

**Not changed:** terms checkbox UX, notification "denied once → open notification settings" fallback path, all `launchSystemScreen` safe-launch semantics, RECOMMENDED optionality.

## 3) DARK-BTN-01 — Dark-Mode button contrast (root cause + fix)

**Root cause analysis.** Two independent failure classes shared one symptom ("buttons unreadable in dark mode"):

| Class | Mechanism | Ratio | Affected |
|---|---|---|---|
| A | Dark `primary` = #1F323F (near-black navy). M3 uses `primary` as the CONTENT color of every `TextButton`/`OutlinedButton` label and border → on surface #151F26 / background #061620 | **≈1.3:1** (invisible) | 40 `TextButton` sites (dialogs: Cancel/Close/Disallow/OK…), `OutlinedButton` (onboarding Allow/Exempt/Enable/Open, ADB step buttons), icon tints, "✓ Granted" tags |
| B | Filled action buttons: `ButtonDefaults.buttonColors(containerColor = BrandOrange)` — white content resolves to ≈2.8:1 (below even the 3:1 large-text floor) | **≈2.8:1** | 12 `Button` sites (Accept & Continue, Continue to App, Unlock, Next, Confirm & Save, Save×3, Export/Import, Save Schedule…), PIN keypad keys, New-Schedule FAB |

**Fix, minimal and total:**
- **Theme-level (one pair fixes class A everywhere):** dark `primary` → `DarkPrimaryInteractive` #9DB8C6 (≈8.05:1 on surface, ≈8.84 on background as content); dark `onPrimary` → `DarkOnPrimaryInteractive` #0A2029 (≈8.07:1 on the new primary, so filled default buttons also pass). The brand navy #1F323F survives where it is actually correct: `primaryContainer` (white on it ≈13.24:1) and the **light** scheme primary (≈13.24:1 on white). Audit of all `colorScheme.primary` consumers: every other usage treats it as content-on-surface or 15%-alpha tint → universally *improved*; the only bg+hardcoded-white consumer (`StepCard` number circle) was re-paired to `onPrimary`.
- **Button-level (class B):** new `theme/AppButtons.kt` → `brandButtonColors()` = #B85700 `BrandOrangeButton` + white (≈4.77:1, WCAG AA normal text) + disabled #B85700@40%/white@60%. Swapped at all 12 `ButtonDefaults.buttonColors(containerColor = BrandOrange…)` sites, both keypad `filledKey = BrandOrange` sets (white digits 28/24sp), and the FAB container. Semantically-distinct buttons kept as-is (error-pair "Disable App Lock"/restore-destructive buttons, onSurface-pair text buttons). StopMe tonal card `BrandOrange@15%` is a surface tint, untouched.
- **Naming tripwire:** `DarkModeButtonsTest` fails the build if `containerColor = BrandOrange` reappears in main sources.

**Known, documented exception (unchanged from v1.0.69):** raw BrandOrange accents on white/light backgrounds reach ≈2.76-2.9:1 (large bold display text / gradient art; PIN-filled states are now #B85700 so the PIN screen passes). Tripwire test keeps white-on-#FF7100 ≥ 2.7:1 so the brand pair can never degrade silently; fixing the hue itself is a brand decision.

## 4) UI-CONSIST-01 — layout/alignment audit

| Location | Issue | Fix |
|---|---|---|
| Onboarding `PermissionRowCard` | Status tag appended inline to the title (`"Title  • Required"`) — wrapped unpredictably, collided with long titles | Title → status line → description stack; tag text on own line; `"Not required on this device"` wording normalized |
| Onboarding copy | Header didn't state mandatory nature; "⚠️ Important" card on TERMS said "you can grant" | Both rewritten to state Required = mandatory, consistently with the gate |
| `BlockerPageHome` a11y banners | Error variant 24dp icon/12dp gap vs success variant 20dp/8dp | Success variant normalized to 24dp/12dp |
| Reliable Accessibility `StatusCard` | Hardcoded #1B5E20/#E65100 8% card tint; white icons on #4CAF50/#FF9800 circles (≈2.5/2.2:1 — below 3:1 non-text floor) | `StatusSuccess`#2E7D32 / `StatusWarning`#E65100 tokens (10% tint); circles same tokens (white ≈4.9/3.6:1) — pinned in tests |
| `StepCard` number circle | Hardcoded `Color.White` text on `primary` → would break to ≈2.3:1 after the dark-primary swap | Text color → `onPrimary` (correct M3 pairing: ≈8.1:1 dark, ≈13.2:1 light) |
| `DiagnosticsCard`/`DiagnosticRow` | Row spacing 6dp vs 8dp elsewhere; long flat component name pushed/clipped the label | 8dp; value takes remaining width, end-aligned, ≤3 lines, ellipsis |
| Profile "Reliable Accessibility" card | Trailing "View" text in BrandOrange ≈2.8:1 on light surface; looked non-interactive | 24dp chevron in `onSurfaceVariant` + contentDescription |

Padding/margins across onboarding were already uniform (24dp outer / 16dp spacing / 12dp card radius / 16dp card padding) — verified, kept.

## 5) Build & verification (release-first, per process)

1. `compileDebugKotlin` + `compileDebugUnitTestKotlin` → BUILD SUCCESSFUL (pre-flight sanity).
2. **`assembleRelease` FIRST** → BUILD SUCCESSFUL in 5 m 15 s → **3,274,757 B** (~0.2 MB < v1.0.71).
   - `apksigner verify --print-certs` → signature valid (debug keystore, as per repo policy).
   - `aapt2 dump badging` → `protect.yourself`, **versionCode 72 / 1.0.72**, minSdk 26, targetSdk 35, launcher intact.
   - `aapt2 dump xmltree` (built APK) → `enableOnBackInvokedCallback=true`, `SYSTEM_ALERT_WINDOW` still absent, permissions unchanged.
   - R8 mapping → `ThemeSwitcherKt.ThemeSwitcherIcon`/`ThemeModeMenuItem` present (normal Compose mangling).
3. `assembleDebug` → BUILD SUCCESSFUL (21,279,731 B).
4. `testDebugUnitTest` → **453/453 pass, 0 failures/errors/skipped, 36 suites** (baseline 435 + 18: +7 OnboardingEnforcement, +4 DarkModeButtons, +4 ThemeSwitcherPlacement, +3 ColorContrast pairs; two v1.0.69 pin tests renamed/extended).
   - One intermediate failure during the round: the new static pin matched its own KDoc quote of the anti-pattern → pin corrected, suite re-run green. No production code involved.

## 6) Regression watchlist (what could plausibly break — and why it didn't)

| Risk | Mitigation / evidence |
|---|---|
| Dark `primary` swap regresses a white-on-primary consumer | Audited all 10 `colorScheme.primary` refs: 8 content-on-surface (improved), 1 alpha-tint, 1 StepCard circle re-paired to `onPrimary`; contrast suite pins both schemes' 12 role pairs |
| Onboarding hard-lockout on broken OEM Settings | Per-row `launchSystemScreen` safe-launch kept; toast fallbacks kept; evaluator fail-open on crash (crash-logged); RECOMMENDED rows optional |
| Gate bounces users mid-session | `onResume` passes `enforceRequiredPermissions = false`; banner + self-heal own the in-session path |
| Old installs (pre-v1.0.66) routed to onboarding | Only when required perms are missing — intended enforcement; checklist starts at PERMISSIONS |
| TopAppBar experimental API | Stable in material3 1.3.x; BOM pinned 2024.10.01 |
| Icon availability | `material-icons-extended` already a dependency; R8 keep behavior verified via mapping |
| Disabled brand buttons illegible | `brandButtonColors()` pins disabled pair; App Lock unlock-button disabled state exercised by `AppLockManagerTest`/`AppLockSessionResetTest` (functional suites green) |
| Source-pin tests brittle to refactors | Pins target stable tokens/strings; failure mode is a loud, specific message |

**All 34 pre-existing suites green unchanged** — OnboardingPermissionsTest (14), BlockScreenReliabilityTest (10), AccessibilityPersistTest (10), ProtectedSystemScreensTest (11), CloseGatePolicyTest (7), OverlayDependencyRemovedTest (9), A11yAnrRegressionTest (13), TraversalBudgetTest (8), CountdownSelfHealTest (7), CrashLoggerInitOrderTest (5), etc.
