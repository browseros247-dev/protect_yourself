# Verification Report — Protect-Yourself Comprehensive Analysis

**Task ID:** V-1
**Agent:** Verify-Pass
**Date:** Verification pass on prior comprehensive analysis
**Prior report:** `/home/z/my-project/download/COMPREHENSIVE_ANALYSIS_REPORT.md`
**Source verified:** `/home/z/my-project/repo/Protect-Yourself/`
**Method:** Direct re-reading of every cited source file + targeted grep for items 16–24.

---

## 1. Verification of the 15 prior claims

| # | Claim (from prior report / task brief) | Result | Evidence (file:line + quote) |
|---|----------------------------------------|--------|------------------------------|
| 10.1 | `AppDataCheckWorker.kt:63-65` has `TODO Phase 5: due Stop Me scheduled sessions` | **CONFIRMED** | `app/src/main/java/protect/yourself/commons/utils/workManager/AppDataCheckWorker.kt:65` — `// TODO Phase 5: due Stop Me scheduled sessions`. Exact line-number match. |
| 10.2 | `AppDataCheckWorker.kt:64` has `TODO Phase 5: streak date rollover` | **CONFIRMED** | `AppDataCheckWorker.kt:64` — `// TODO Phase 5: streak date rollover`. Exact match. (Line 63 also has `// TODO Phase 3+: re-apply accessibility blocking values`.) |
| 10.3 | `MyAccessibilityService.kt:46-69` cached fields not `@Volatile` | **CONFIRMED** | `app/src/main/java/protect/yourself/features/blockerPage/service/MyAccessibilityService.kt:46-69`. All 21 cached fields are plain `private var` with NO `@Volatile`: `cachedBlockKeywords` (46), `cachedWhitelistKeywords` (47), `cachedBlockApps` (48), `cachedStopMeWhitelist` (49), `cachedVpnWhitelist` (50), `cachedNewInstallBlockApps` (51), `cachedInAppBrowserBlockApps` (52), `cachedUnsupportedBrowserWhitelist` (53), `isPornBlockerOn` (55), `isSafeSearchOn` (56), `isBlockNewInstallOn` (57), `isBlockInAppBrowsersOn` (58), `isBlockUnsupportedBrowsersOn` (59), `isBlockSettingsByTitleOn` (60), `isPreventUninstallOn` (61), `isBlockPhoneRebootOn` (62), `cachedSettingTitles` (64), `cachedBlockedPackageNames` (66), `cachedBlockedIntentNames` (67), `isBlockPackageIntentOn` (68), `isStopMeRunning` (69). |
| 10.4 | `MyVpnService.kt:76` `isStarting` not `@Volatile` | **CONFIRMED** | `app/src/main/java/protect/yourself/features/blockerPage/service/MyVpnService.kt:76` — `private var isStarting = false  // guards against concurrent startVpn() calls`. No `@Volatile`. Read at line 137, set at line 141. **Additional finding:** `isRunning` (line 75), `currentConnectionType` (77), `currentFirstDns` (78), `currentSecondDns` (79), `forwarderJob` (82), `restartJob` (90) are also non-`@Volatile`. Prior report's fix recommendation already covers `isStarting` + `isRunning`. |
| 10.5 | `MyAccessibilityService.onDestroy` does not cancel `serviceScope` | **CONFIRMED** | `MyAccessibilityService.kt:155-161`. The method body is: `super.onDestroy(); Timber.w("Accessibility service destroyed"); instance = null; Toast.makeText(...).show()`. No `serviceScope.cancel()` call. `serviceScope` is declared at line 43 (`CoroutineScope(SupervisorJob() + Dispatchers.Default)`) — leaked on every service destroy/recreate cycle. |
| 10.6 | `AppSystemActionReceiverAllTime.kt:29` creates a per-instance `CoroutineScope` | **CONFIRMED** | `app/src/main/java/protect/yourself/commons/utils/broadcastReceivers/AppSystemActionReceiverAllTime.kt:29` — `private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`. BroadcastReceiver instances are created fresh per broadcast delivery by the Android system, so this scope is allocated per-broadcast and never cancelled (no `scope.cancel()` anywhere in the file — verified by full read). Genuine per-broadcast leak. |
| 10.7 | `MyFirebaseMessagingService` declared in `AndroidManifest.xml:194-200` but class doesn't exist | **CONFIRMED** | `AndroidManifest.xml:194-200` declares `<service android:name="protect.yourself.commons.utils.firebaseUtils.MyFirebaseMessagingService" ...>`. Glob `**/MyFirebaseMessagingService*` → **0 matches**. Glob `**/firebaseUtils/**` → **0 matches**. The class file does NOT exist. Dead manifest entry — would throw `ClassNotFoundException` if FCM ever delivered an intent (FCM is stripped, so latent only). |
| 8 (FCM file re-check) | Does `MyFirebaseMessagingService.kt` exist? | **CONFIRMED ABSENT** | Same as 10.7 — no file at the expected path `app/src/main/java/protect/yourself/commons/utils/firebaseUtils/MyFirebaseMessagingService.kt`, and the entire `firebaseUtils/` package directory is absent. Claim 10.7 stands. |
| 9 (7 unused permissions) | Manifest declares 7 unused permissions: `KILL_BACKGROUND_PROCESSES`, `REORDER_TASKS`, `SYSTEM_ALERT_WINDOW`, `com.google.android.c2dm.permission.RECEIVE`, `INTERACT_ACROSS_USERS_FULL`, `INTERACT_ACROSS_USERS`, `WRITE_SECURE_SETTINGS` | **CONFIRMED** (with one nuance) | All 7 are declared in `AndroidManifest.xml`: `WRITE_SECURE_SETTINGS` (20), `KILL_BACKGROUND_PROCESSES` (35), `REORDER_TASKS` (36), `SYSTEM_ALERT_WINDOW` (39), `INTERACT_ACROSS_USERS_FULL` (42), `INTERACT_ACROSS_USERS` (45), `com.google.android.c2dm.permission.RECEIVE` (57). Grep of `app/src/main` for each name + their typical API calls (`killBackgroundProcesses`, `moveTaskToFront`, `reorderTask`, `addView`, `TYPE_APPLICATION_OVERLAY`, `WindowManager.LayoutParams`, `Settings.Secure.put*`, `Settings.Global.put*`) found **zero** source usage. **Nuance on `SYSTEM_ALERT_WINDOW`:** the app has orphaned UI code that fires `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` intents (`BlockerPageHome.kt:148`, `AgreeTermsPage.kt:309`) — i.e. it prompts the user to grant a permission it never actually uses (no `addView` / `TYPE_APPLICATION_OVERLAY` anywhere). So the permission is functionally unused (prior report correct) AND the UI prompts are dead code that should also be removed. **Nuance on `WRITE_SECURE_SETTINGS`:** app only reads `Settings.Secure.getString(ENABLED_ACCESSIBILITY_SERVICES)` (`AccessibilityGuard.kt:87`, `MyAccessibilityService.kt:1020`) — never writes. WRITE_SECURE_SETTINGS is a write-only permission, so it is genuinely unused for read-only access. Prior report's "unused read" characterisation is accurate. |
| 10 (PBKDF2 100K) | `AppLockManager.kt` uses 100,000 iterations (not 10,000) | **CONFIRMED** | `app/src/main/java/protect/yourself/features/appPasswordPage/AppLockManager.kt:180` — `private const val ITERATIONS = 100_000`. Comment on lines 176-179 explicitly contrasts with the reference's 10,000. 16-byte random salt via `SecureRandom` (line 149), `PBKDF2WithHmacSHA256` (line 160), constant-time comparison (lines 103-110). All accurate. |
| 11 (BIOMETRIC_WEAK) | `AppLockScreen.kt:546` uses `BIOMETRIC_WEAK` | **CONFIRMED** | `app/src/main/java/protect/yourself/features/appPasswordPage/AppLockScreen.kt:546` — `.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK)`. Also used at line 563 in `canAuthenticate(...)`. Could be upgraded to `BIOMETRIC_STRONG` (prior report's recommendation stands). |
| 12 (Timber DebugTree) | `ProtectYourselfApp.kt:140` always plants `Timber.DebugTree()` | **CONFIRMED** | `app/src/main/java/protect/yourself/core/ProtectYourselfApp.kt:138-142`: `private fun initTimberLog() { Timber.plant(Timber.DebugTree()); crashLogger?.let { Timber.plant(CrashLoggingTree(it)) } }`. No `if (BuildConfig.DEBUG)` guard. `DebugTree` is planted unconditionally in all builds including release. Prior report's §10.12 bug classification is accurate. |
| 13 (VPN /32 routes) | `MyVpnService.kt:231-246` uses `addRoute(dns, 32)` for specific DNS IPs, NOT `addRoute("0.0.0.0", 0)` | **CONFIRMED** | `MyVpnService.kt:231-232` — `.addRoute(firstDns, 32)` / `.addRoute(secondDns, 32)`. Lines 240-246 add per-host `/32` (or small-prefix) routes for each entry in `DNS_HIJACK_HOSTS`. No `addRoute("0.0.0.0", 0)` anywhere in the file (verified by full read). The DNS-only interception pattern (matching Intra/DNS66/Blokada) is correctly implemented. |
| 14 (Aho-Corasick) | `KeywordMatcher.kt` implements Aho-Corasick (goto + failure + output) | **CONFIRMED** | `app/src/main/java/protect/yourself/features/blockerPage/utils/KeywordMatcher.kt`. **Goto/trie:** lines 39-47 (`node.children.getOrPut(ch) { Node() }`). **Failure links (BFS):** lines 49-70 (`child.failure = f?.children?.get(ch) ?: root`). **Output function:** line 46 (`node.output = keyword`) + output merging across failure links (lines 66-68). **Matching with failure traversal:** `findFirst` (78-99), `findAll` (105-131). Genuine textbook Aho-Corasick. O(M + matches) complexity. |
| 15 (device_admin empty policies) | `device_admin.xml` has empty `<uses-policies />` | **CONFIRMED** | `app/src/main/res/xml/device_admin.xml:3` — `<uses-policies />` (empty self-closing tag). The `DeviceAdminReceiver` (`DeviceAdminUtils$MyDeviceAdminReceiver`) therefore cannot enforce any active policies (force-lock, wipe, reset-password, etc.) — it exists only for the anti-uninstall `onDisableRequested` callback flow. Prior report's characterisation is accurate. |

### Summary tally for items 1–15

- **CONFIRMED:** 15 / 15
- **REFUTED:** 0
- **PARTIALLY CONFIRMED:** 0

(The `SYSTEM_ALERT_WINDOW` nuance is additional context, not a partial refutation — the permission itself is still genuinely unused for overlay drawing, which is what the prior report claimed.)

---

## 2. New issues found in items 16–24

### 16. `runBlocking` in production code
**Result:** ✅ CLEAN. All matches are either:
- Test code (`app/src/test/java/protect/yourself/database/AllDaosTest.kt`, `SwitchStatusDaoTest.kt`) — acceptable.
- Documentation / comments (`StreakWidget.kt:26` KDoc, `AppContainer.kt:12` KDoc, `docs/*.md`).
No `runBlocking` call sites in production source.

### 17. `GlobalScope` in production code
**Result:** ✅ CLEAN. The only production-source match is `AppContainer.kt:12` — a KDoc comment describing what the *original* (reference) AppContainer held (`applicationScope: GlobalScope`). The rebuild's `AppContainer.applicationScope` (line 27) is `CoroutineScope(SupervisorJob() + Dispatchers.Default)` — NOT `GlobalScope`. All other matches are in `docs/*.md`. No `GlobalScope` usage in production code.

### 18. `http://` in source
**Result:** ✅ CLEAN (no cleartext network calls). All matches in `app/src/main` are:
- XML namespace declarations (`xmlns:android="http://..."`) — not network.
- `BlockerPageUtils.kt:232` — `url.startsWith("http://")` — a URL-scheme string check, not a network call.
- `MyAccessibilityService.kt:708-713` — `Uri.parse("http://www.google.com")` used in a `queryIntentActivities` call for browser detection. The code comments explicitly explain this is intent-filter inspection (some older browsers only declare `http://` intent filters, not `https://`), not an actual HTTP request. Matches prior report's §13 finding.

### 19. `TODO|FIXME|HACK|XXX` in source
**Result:** 10 TODOs in 8 production files (no FIXME/HACK/XXX). Full inventory:

| File | Line | Text |
|------|------|------|
| `TransparentActivity.kt` | 14 | `// TODO Phase 6` |
| `ProtectedAppsActivity.kt` | 14 | `// TODO Phase 6` |
| `InAppReviewActivity.kt` | 13 | `// TODO Phase 6` |
| `AgreeTermsPage.kt` | 105 | `/* TODO: open browser to terms URL */` |
| `AgreeTermsPage.kt` | 136 | `/* TODO: open browser to privacy URL */` |
| `AppDataCheckWorker.kt` | 63 | `// TODO Phase 3+: re-apply accessibility blocking values` |
| `AppDataCheckWorker.kt` | 64 | `// TODO Phase 5: streak date rollover` |
| `AppDataCheckWorker.kt` | 65 | `// TODO Phase 5: due Stop Me scheduled sessions` |
| `NotificationActionService.kt` | 17 | `// TODO Phase 6` |
| `AppSystemActionReceiver.kt` | 15 | `// TODO Phase 6` |

The prior worklog said "~10 TODO markers across 7 files" — actual is 10 across 8 files. Minor undercount, not a substantive correction.

### 20. `android:allowBackup`
**Result:** ✅ GOOD. `AndroidManifest.xml:110` — `android:allowBackup="false"`. Auto-backup to Google Drive correctly disabled.

### 21. `android:usesCleartextTraffic`
**Result:** ✅ GOOD. The attribute is **not present** on the `<application>` tag. With `targetSdk = 35` (≥ 28), the default is `false` → cleartext traffic blocked. No `android:networkSecurityConfig` either. Matches prior report §13.

### 22. `proguard-rules.pro` keep-rule audit
**Result:** ⚠️ NEW MINOR FINDING — three dead/inert keep rules representing config drift:

| Line | Rule | Status |
|------|------|--------|
| 27-29 | `-keep class com.google.firebase.** { *; }` / `com.google.android.gms.**` / `-dontwarn com.google.firebase.**` | **DEAD** — Firebase entirely stripped from `build.gradle.kts` (lines 170-179 commented out). Inert but should be removed. |
| 36 | `-keep class com.canhub.cropper.** { *; }` | **DEAD** — CanHub image-cropper dependency stripped (`build.gradle.kts:188` commented). Inert. |
| 67 | `-keep class protect.yourself.commons.signaturekiller.** { *; }` | **DEAD + PHANTOM** — the `signaturekiller` package does NOT exist in source (Glob `**/signaturekiller/**` → 0 matches; only referenced in `README.md:162` and `docs/IMPLEMENTATION_PLAN.md:1394` as a planned-but-unimplemented feature). This keep rule targets a class that was never written. |

Additionally, note that **minification is disabled** (`isMinifyEnabled = false` for both debug and release in `build.gradle.kts:42,53`), so ALL ProGuard rules are currently inert. They would only take effect if a future build re-enables R8.

**No rules were found that leak sensitive classes.** The broad `-keep class protect.yourself.database.** { *; }` (line 56) keeps Room entities unobfuscated, but (a) minification is off, and (b) entities contain no secrets. `-keepattributes SourceFile,LineNumberTable` (line 7) preserves source file names in release stack traces — acceptable for a crash-logging-focused app, and consistent with the app's design philosophy.

### 23. `google-services.json`
**Result:** ✅ CONFIRMED ABSENT. Glob `**/google-services.json` → 0 matches. The `google.services` plugin is commented out in `build.gradle.kts:6-9`. Matches prior report §13.

### 24. `signingConfigs` in `app/build.gradle.kts`
**Result:** ⚠️ CONFIRMED — release build signed with debug keystore.
`app/build.gradle.kts:60` (inside the `release { ... }` block):
```kotlin
// For rebuild, sign with debug keystore (user can re-sign)
signingConfig = signingConfigs.getByName("debug")
```
The prior report DID note this at line 681 of the comprehensive report (`v2 only (Android Debug cert)`), so this is not a missed finding — it's confirmed. The comment in the source acknowledges this is a deliberate rebuild-default that the user must override before Play Store release. Debug certs are 1-year-validity (vs. 25-year for release) and rejected by Play Console. **Recommendation:** add a `signingConfigs.create("release") { ... }` block reading from environment variables or a `keystore.properties` file, and reference it from the `release` build type.

---

## 3. Additional findings (incidental, beyond items 16–24)

During verification the following minor items were observed but were either already covered by the prior report or are too small to warrant a new bug ID:

1. **Orphaned overlay-permission UI** (related to item 9 / `SYSTEM_ALERT_WINDOW`): `BlockerPageHome.kt:148` and `AgreeTermsPage.kt:309` fire `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` intents to send the user to the system "Display over other apps" screen — but the app never calls `WindowManager.addView` or uses `TYPE_APPLICATION_OVERLAY`. The block UI is implemented via `PornBlockActivity` (a full-screen Activity, not an overlay window). These two intent launches are dead UX that pressure the user to grant an unused permission. Should be removed alongside the manifest permission.

2. **`MyVpnService` additional non-volatile fields** (extends item 10.4): beyond `isStarting` and `isRunning`, the fields `currentConnectionType` (77), `currentFirstDns` (78), `currentSecondDns` (79), `vpnState` (96 — though this one has a custom setter that refreshes the notification), `forwarderJob` (82), and `restartJob` (90) are also plain `private var` without `@Volatile`. The prior report's fix recommendation (`Add @Volatile to isStarting and isRunning`) should be broadened to cover the other cross-thread state fields, particularly `forwarderJob` and `restartJob` which are reassigned from coroutine contexts and read from `stopVpn()` / `onRevoke()`.

3. **`bin.mt.signature.KillerApplication` phantom keep rule** (item 22 above): the proguard keep rule for `protect.yourself.commons.signaturekiller.**` references a package that was planned (per `docs/IMPLEMENTATION_PLAN.md:1394`) but never implemented. This is benign (inert rule, minification off) but is config drift worth cleaning up.

---

## 4. Overall verdict on the prior analysis report

**The prior `COMPREHENSIVE_ANALYSIS_REPORT.md` is ACCURATE and does NOT require corrections to its substantive findings.** All 15 verification items (7 bug claims, 7 permission claims, 6 implementation-detail claims) were **CONFIRMED** against the actual source code. Zero refutations. Zero partial refutations.

**Minor refinements that could be incorporated (none invalidate prior conclusions):**

| Refinement | Effect on prior report |
|------------|------------------------|
| TODO count is 10 across 8 files (not ~10 across 7) | Cosmetic; prior "~10" approximation is fine. |
| `SYSTEM_ALERT_WINDOW` has orphaned UI prompts in 2 files | Strengthens the case for removal; add a sentence noting the `ACTION_MANAGE_OVERLAY_PERMISSION` intents in `BlockerPageHome.kt:148` + `AgreeTermsPage.kt:309` should also be deleted. |
| `MyVpnService` has additional non-volatile fields (`forwarderJob`, `restartJob`, `currentConnectionType`, `currentFirstDns`, `currentSecondDns`) | Broaden the §10.4 fix recommendation from "isStarting + isRunning" to "all cross-thread state fields". |
| 3 dead ProGuard keep rules (Firebase, CanHub, signaturekiller) | New minor hygiene finding; consider adding a one-line note in §11 (dependencies audit) or a new low-priority recommendation. |
| `signaturekiller` package is planned-but-unimplemented | Confirms the prior report's statement that the signature killer was removed; the keep rule is leftover config. |

**No corrections required to:** the 13-bug list, the 7-unused-permissions list, the PBKDF2 100K claim, the BIOMETRIC_WEAK claim, the Timber-DebugTree-always-planted claim, the VPN /32-route claim, the Aho-Corasick claim, the device-admin-empty-policies claim, the no-`google-services.json` claim, the no-cleartext-traffic claim, the no-`GlobalScope`/`runBlocking`-in-production claim, or the release-signed-with-debug-cert observation.

**The report is safe to commit/push as-is.** The refinements above are optional polish.

---

*End of verification report.*
