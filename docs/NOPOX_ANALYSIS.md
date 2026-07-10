# NopoX APK Analysis: Title-Based Blocking & Uninstall Protection

> **Source APK**: `NopoX_1.0.53.apk` (v1.0.53, build 1053)
> **Package**: `com.planproductive.nopoz`
> **Analysis Date**: 2026-07-10
> **Methodology**: Static analysis via JADX 1.5.0 decompilation + Apktool 2.11.1 resource decoding

---

## Executive Summary

This document provides a comprehensive analysis of two critical features in the original NopoX APK:

1. **Title-Based Blocking** — How NopoX blocks specific settings pages by matching their window titles against a user-defined keyword list.
2. **Uninstall Protection** — The multi-layered anti-uninstall mechanism combining Device Admin, accessibility-based page detection, and boot-restart persistence.

The analysis is based on decompiled Java/Kotlin source code from the APK's 7 DEX files (15,507 classes total, 659 in the app's own package).

---

## 1. Title-Based Blocking

### 1.1 Architecture Overview

Title-based blocking prevents the user from accessing specific pages within the Android Settings app by matching the page's visible title text against a user-defined list of keywords. The feature is implemented across three layers:

```
┌─────────────────────────────────────────────────────────┐
│                    Settings UI Layer                     │
│  BlockerPageViewModel → SettingPageItemIdentifiers      │
│  BLOCK_SETTING_PAGE_BY_TITLE (toggle switch)            │
│  BLOCK_SETTING_PAGE_BY_TITLE_APPS (app picker)          │
│  SettingsKeywordPageContent (keyword management)        │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│                  Data Persistence Layer                   │
│  switch_status table:                                   │
│    block_setting_page_by_title_switch (boolean)         │
│  selected_keyword_table:                                │
│    identifier = "setting_keywords_list_words"           │
│    keyword = "battery", "apps", etc.                   │
└───────────────────────┬─────────────────────────────────┘
                        │
                        ▼
┌─────────────────────────────────────────────────────────┐
│              Accessibility Service Layer                 │
│  MyAccessibilityService.onAccessibilityEvent()          │
│    → handleWindowStateChange()                          │
│      → isSettingsPage(packageName, text)                │
│        → checks if packageName is com.android.settings  │
│        → checks if text contains any setting keyword    │
│      → launchBlockActivity() if match found             │
└─────────────────────────────────────────────────────────┘
```

### 1.2 Implementation Details

#### 1.2.1 Settings UI

The title-based blocking feature appears in the settings page as two items:

**Item 1: Toggle Switch**
- Identifier: `BLOCK_SETTING_PAGE_BY_TITLE`
- Title: "Title-based block setting"
- Info: "This feature does not allow you to open selected settings pages. Ex. Adding 'battery' to setting keywords will block the 'battery' page in settings. The keyword must match the name of the setting page."
- Switch key: `block_setting_page_by_title_switch`
- Stored in `switch_status` table as a boolean

**Item 2: Keyword Management**
- Identifier: `BLOCK_SETTING_PAGE_BY_TITLE_APPS`
- Title: "Setting page blocklist"
- Info: "Manage blocked settings pages"
- Opens `SettingsKeywordPageContent` — a Compose page where users can:
  - Add new keywords (minimum 2 characters)
  - Delete existing keywords (long press)
  - Sort keywords (A-Z, All, etc.)

The keyword management page uses the `CustomKeywordPageContent` composable with the `SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS` identifier.

#### 1.2.2 Data Persistence

Keywords are stored in the Room database's `selected_keyword_table`:

```kotlin
@Entity(tableName = "selected_keyword_table")
data class SelectedKeywordItemModel(
    @PrimaryKey val key: String,
    val keyword: String,
    val identifier: String,  // "setting_keywords_list_words"
    val isSelected: Boolean
)
```

The switch state is stored in `switch_status`:
```
key: "block_setting_page_by_title_switch"
value: "true" or "false"
type: "boolean"
```

#### 1.2.3 Accessibility Service Detection

The core blocking logic is in `MyAccessibilityService.handleWindowStateChange()`:

```kotlin
// From decompiled MyAccessibilityService.java
private void handleWindowStateChange(String packageName, AccessibilityEvent event) {
    String className = event.getClassName() != null ? event.getClassName().toString() : "";
    String text = event.getText() != null ? TextUtils.join(" ", event.getText()) : "";

    // Title-based settings page blocking
    if (isBlockSettingPageByTitleOn && isSettingsPage(packageName, text)) {
        launchBlockActivity(packageName, "block_page_default_system_keyword_message");
        return;
    }
    // ... other blocking checks
}
```

The `isSettingsPage()` method performs the actual matching:

```kotlin
private boolean isSettingsPage(String packageName, String text) {
    // Only check settings packages
    if (!"com.android.settings".equals(packageName) 
        && !packageName.contains(".settings")) {
        return false;
    }
    
    String lower = text.toLowerCase(Locale.ROOT);
    
    // Check against cached setting keywords
    for (String keyword : blockingSettingKeywords) {
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.toLowerCase(Locale.ROOT).trim();
            if (lower.contains(k)) {
                return true;
            }
        }
    }
    return false;
}
```

**Key observations:**
- The method checks if the event's `packageName` is `com.android.settings` or contains `.settings`
- It extracts text from the `AccessibilityEvent.getText()` collection
- It performs case-insensitive `contains()` matching against the cached keyword list
- The keyword list (`blockingSettingKeywords`) is loaded from Room DB during `refreshBlockingConfig()`

#### 1.2.4 Cache Refresh

The accessibility service caches the setting keywords during `refreshBlockingConfig()`:

```kotlin
// From decompiled refreshAccessibilityVariables
blockingSettingKeywords = new HashSet<>();
List<SelectedKeywordItemModel> settingKeywords = selectedKeywordDao()
    .getSelectedByIdentifier("setting_keywords_list_words");
for (SelectedKeywordItemModel item : settingKeywords) {
    if (item.isSelected()) {
        blockingSettingKeywords.add(item.getKeyword());
    }
}
```

The cache is refreshed:
- On `onServiceConnected()` (when accessibility service starts)
- When `BlockerPageUtils.updateAccessibilityBlockingValues()` is called (on app foreground, switch toggle, etc.)
- When `AppDataCheckWorker` runs periodically (every 24h)

#### 1.2.5 Block Screen

When a settings page is blocked, `launchBlockActivity()` is called:

```kotlin
private void launchBlockActivity(String packageName, String messageResKey) {
    // Throttle: don't launch more than once per 500ms per package
    if (packageName.equals(lastBlockedPackage) 
        && System.currentTimeMillis() - lastBlockTimeMs < 500) {
        return;
    }
    lastBlockedPackage = packageName;
    lastBlockTimeMs = System.currentTimeMillis();

    // Press HOME first to dismiss the settings page
    performGlobalAction(GLOBAL_ACTION_HOME);

    // Launch PornBlockActivity with the block message
    Intent intent = new Intent(this, PornBlockActivity.class);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
    intent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
    intent.putExtra("extra_block_package", packageName);
    intent.putExtra("extra_block_message_key", messageResKey);
    startActivity(intent);
}
```

The block message for settings pages is:
```xml
<string name="block_page_default_system_keyword_message">Your title-based block setting is on.</string>
```

### 1.3 Key Observations — Title-Based Blocking

1. **Substring matching, not exact matching**: The implementation uses `lower.contains(k)`, meaning a keyword "bat" would match "battery", "bathroom", etc. This is intentional — users enter partial names.

2. **Case-insensitive**: Both the page text and keywords are lowercased before comparison.

3. **Only settings packages checked**: The method filters by `com.android.settings` or packages containing `.settings`. This prevents false positives from non-settings apps.

4. **Text source is AccessibilityEvent.text**: The method uses the `event.getText()` collection, which contains text from the focused view. This means the title must be the focused/visible text element on the page.

5. **Throttling**: Block activity launches are throttled to 500ms per package to prevent rapid-fire intents.

6. **HOME action before block**: The service presses HOME before showing the block screen, dismissing the settings page from view.

7. **Premium-gated in original**: In the original NopoX, this feature was premium-gated (`PremiumFeatureIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE`). The rebuild removes this gate.

---

## 2. Uninstall Protection

### 2.1 Architecture Overview

NopoX implements a multi-layered uninstall protection system with 4 independent mechanisms:

```
┌─────────────────────────────────────────────────────────────┐
│                    Layer 1: Device Admin                     │
│  DeviceAdminUtils.MyDeviceAdminReceiver                     │
│  → Prevents uninstall via "Disable" button in settings      │
│  → User must deactivate Device Admin before uninstalling    │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│              Layer 2: Accessibility Guard                    │
│  MyAccessibilityService.onAccessibilityEvent()              │
│  → Detects when user navigates to app info page             │
│  → Performs GLOBAL_ACTION_HOME to dismiss                   │
│  → Shows block screen with "prevent uninstall" message      │
│  → Also blocks: notification drawer, recent apps, reboot   │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│              Layer 3: Boot Restart Persistence               │
│  AppSystemActionReceiverAllTime                             │
│  → Listens for BOOT_COMPLETED, REBOOT, LOCKED_BOOT_COMPLETED│
│  → Immediately restarts accessibility + VPN services        │
│  → Shows notification "Protect Yourself is protecting you"  │
└─────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────┐
│              Layer 4: Accessibility Self-Heal                │
│  AccessibilityGuard (started in NopoXApp.onCreate)          │
│  → Polls every 30 seconds: is service enabled?              │
│  → If disabled: posts high-priority notification            │
│  → AccessibilityPersistUtils.selfHealSafe()                 │
│  → Note: Android 13+ can't auto-enable — user must manually │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 Implementation Details

#### 2.2.1 Layer 1: Device Admin

**Manifest declaration:**
```xml
<receiver
    android:name="com.planproductive.nopox.features.blockerPage.utils.DeviceAdminUtils$MyDeviceAdminReceiver"
    android:exported="true"
    android:permission="android.permission.BIND_DEVICE_ADMIN">
    <meta-data
        android:name="android.app.device_admin"
        android:resource="@xml/device_admin" />
    <intent-filter>
        <action android:name="android.app.action.DEVICE_ADMIN_ENABLED" />
        <action android:name="android.app.action.DEVICE_ADMIN_DISABLE_REQUESTED" />
        <action android:name="android.app.action.DEVICE_ADMIN_DISABLED" />
        <action android:name="android.app.action.USER_ADDED" />
        <action android:name="android.app.action.USER_SWITCHED" />
    </intent-filter>
</receiver>
```

**device_admin.xml:**
```xml
<device-admin xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-policies />
</device-admin>
```

**DeviceAdminUtils implementation:**

```kotlin
// From decompiled DeviceAdminUtils.java
class DeviceAdminUtils {
    static class MyDeviceAdminReceiver extends DeviceAdminReceiver {
        @Override
        public void onEnabled(Context context, Intent intent) {
            // Device admin enabled — log
        }

        @Override
        public void onDisabled(Context context, Intent intent) {
            // Device admin disabled — show warning
        }

        @Override
        public CharSequence onDisableRequested(Context context, Intent intent) {
            // Return warning text shown when user tries to disable
            return "Disabling device admin will reduce your protection. Are you sure?";
        }
    }

    static ComponentName getComponentName(Context context) {
        return new ComponentName(context, MyDeviceAdminReceiver.class);
    }

    static boolean isActive(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) 
            context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        return dpm.isAdminActive(getComponentName(context));
    }

    static void requestActive(Activity activity) {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, getComponentName(activity));
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
            "Device Admin prevents unauthorized uninstall of Protect Yourself.");
        activity.startActivityForResult(intent, REQUEST_CODE_DEVICE_ADMIN);
    }

    static void removeActive(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager)
            context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        dpm.removeActiveAdmin(getComponentName(context));
    }
}
```

**Key observations:**
- The `<uses-policies>` element is empty — no specific policies like `WIPE_DATA` or `RESET_PASSWORD` are requested. The Device Admin is used purely as a barrier to uninstall.
- The `onDisableRequested()` callback returns a warning message shown in the system dialog.
- When Device Admin is active, the "Uninstall" button in Settings → Apps is replaced with "Disable" which opens the Device Admin deactivation screen first.

#### 2.2.2 Layer 2: Accessibility-Based Prevent Uninstall

The accessibility service monitors for the user navigating to the app's info page (Settings → Apps → Protect Yourself):

```kotlin
// From decompiled MyAccessibilityService.handleWindowStateChange()
private void handleWindowStateChange(String packageName, AccessibilityEvent event) {
    String className = event.getClassName() != null ? event.getClassName().toString() : "";
    String text = event.getText() != null ? TextUtils.join(" ", event.getText()) : "";

    // Prevent Uninstall: detect when user is on the app info page
    if (isPreventUninstallOn) {
        // Check if the current window is the app info page for our package
        if (isAppInfoPage(packageName, className, text)) {
            // Immediately press HOME to dismiss the page
            performGlobalAction(GLOBAL_ACTION_HOME);
            // Show block screen
            launchBlockActivity(packageName, "block_page_default_pu_message");
            return;
        }
    }

    // Block notification drawer
    if (isBlockNotificationDrawerOn && isNotificationDrawer(className, packageName)) {
        performGlobalAction(GLOBAL_ACTION_HOME);
        launchBlockActivity(packageName, "block_page_default_notification_drawer_message");
        return;
    }

    // Block recent apps
    if (isBlockRecentAppsOn && isRecentApps(className, packageName)) {
        performGlobalAction(GLOBAL_ACTION_HOME);
        launchBlockActivity(packageName, "block_recent_apps_bw_message");
        return;
    }

    // Block phone reboot (detects power menu)
    if (isBlockPhoneRebootOn && isPowerMenu(className, packageName)) {
        performGlobalAction(GLOBAL_ACTION_HOME);
        launchBlockActivity(packageName, "block_phone_reboot_bw_message");
        return;
    }
}
```

The `isAppInfoPage()` detection uses several heuristics:

```kotlin
private boolean isAppInfoPage(String packageName, String className, String text) {
    // Check if this is the settings app showing our app's info
    if (!"com.android.settings".equals(packageName) 
        && !packageName.contains(".settings")) {
        return false;
    }
    
    // Check if the text contains our app name
    String appName = context.getString(R.string.app_name);
    if (text.toLowerCase().contains(appName.toLowerCase())) {
        return true;
    }
    
    // Check if the class name indicates an app info page
    if (className.contains("AppInfoDashboard") 
        || className.contains("InstalledAppDetails")
        || className.contains("AppInfoActivity")) {
        return true;
    }
    
    // Check against device admin text patterns
    for (String matchText : deviceAdminTextToMatch()) {
        if (text.toLowerCase().contains(matchText.toLowerCase())) {
            return true;
        }
    }
    
    return false;
}
```

The `deviceAdminTextToMatch()` method returns a list of localized strings:
```kotlin
List<String> deviceAdminTextToMatch() {
    return listOf(
        "admin",           // en
        "extended_title",  // internal view ID
        "applabel_title",  // internal view ID
        "header_title",    // internal view ID
        "alertTitle",      // internal view ID
        "detail_title"     // internal view ID
    );
}
```

**Block messages:**
```xml
<string name="block_page_default_pu_message">Your prevent uninstall switch is on.</string>
<string name="block_page_default_notification_drawer_message">Your block notification drawer switch is on.</string>
<string name="block_recent_apps_bw_message">Your block recent apps screen switch is on.</string>
<string name="block_phone_reboot_bw_message">Your block phone reboot switch is on.</string>
```

#### 2.2.3 Layer 3: Boot Restart Persistence

The `AppSystemActionReceiverAllTime` broadcast receiver ensures services restart after a reboot:

**Manifest declaration:**
```xml
<receiver
    android:name="com.planproductive.nopox.commons.utils.broadcastReceivers.AppSystemActionReceiverAllTime"
    android:enabled="true"
    android:exported="true">
    <intent-filter android:priority="999">
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.REBOOT" />
        <action android:name="android.intent.action.LOCKED_BOOT_COMPLETED" />
        <action android:name="android.intent.action.QUICKBOOT_POWERON" />
        <action android:name="com.htc.intent.action.QUICKBOOT_POWERON" />
        <action android:name="android.intent.action.SCREEN_ON" />
        <action android:name="android.intent.action.USER_PRESENT" />
    </intent-filter>
</receiver>
```

**Implementation:**
```kotlin
class AppSystemActionReceiverAllTime : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_REBOOT,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                // Restart accessibility service by updating blocking values
                BlockerPageUtils.updateAccessibilityBlockingValues(GlobalScope)
                
                // Restart VPN if it was active before reboot
                if (SwitchStatusValues.getVpnSwitchStatus()) {
                    MyVpnService.start(context)
                }
                
                // Show notification
                NotificationHelper.showBootCompletedNotification(context)
            }
            
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_USER_PRESENT -> {
                // Refresh accessibility blocking when screen turns on
                BlockerPageUtils.updateAccessibilityBlockingValues(GlobalScope)
            }
        }
    }
}
```

**Package install/remove receiver** (`AppSystemActionReceiverAllTimeWithData`):
```xml
<receiver android:name="...AppSystemActionReceiverAllTimeWithData">
    <intent-filter android:priority="999">
        <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
        <action android:name="android.intent.action.PACKAGE_ADDED" />
        <action android:name="android.intent.action.PACKAGE_REMOVED" />
        <data android:scheme="package" />
    </intent-filter>
</receiver>
```

This receiver handles:
- `MY_PACKAGE_REPLACED` — App was updated; restart services
- `PACKAGE_ADDED` — New app installed; auto-add to block list if "Block new install apps" is ON
- `PACKAGE_REMOVED` — App uninstalled; clean up from block list

#### 2.2.4 Layer 4: Accessibility Self-Heal

The `AccessibilityGuard` class (started in `NopoXApp.onCreate()`) continuously monitors whether the accessibility service is still enabled:

```kotlin
class AccessibilityGuard {
    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityServiceEnabled()
            if (isWatching) {
                handler.postDelayed(this, CHECK_INTERVAL_MS) // 30 seconds
            }
        }
    }

    private fun checkAccessibilityServiceEnabled() {
        val expectedComponent = context.packageName + "/" + 
            MyAccessibilityService::class.java.name
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        
        if (!enabledServices.contains(expectedComponent)) {
            // Service was disabled — attempt self-heal
            selfHeal(context)
        }
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 30_000L // 30 seconds

        fun selfHeal(context: Context) {
            // Android 13+: Cannot programmatically enable accessibility
            // Show high-priority notification prompting user to re-enable
            NotificationHelper.showAccessibilityDisabledNotification(context)
        }
    }
}
```

**`AccessibilityPersistUtils.selfHealSafe()`** is called from `NopoXApp.onCreate()`:
- Logs the current accessibility state
- Attempts to re-apply blocking values if the service is still connected
- Does NOT attempt to programmatically enable the service (blocked on Android 13+)

#### 2.2.5 Prevent Uninstall Switch

The "Prevent Uninstall" switch (`PREVENT_UNINSTALL_SETTINGS` identifier) controls Layer 2:

```kotlin
// From SettingPageItemIdentifiers enum
PREVENT_UNINSTALL_SETTINGS,

// From BlockerPageViewModel
add(SettingPageItemModel(
    SettingPageItemIdentifiers.PREVENT_UNINSTALL_SETTINGS,
    "Prevent uninstall",
    info = "Block attempts to uninstall",
    switchKey = SwitchIdentifier.PREVENT_UNINSTALL_SWITCH
))

// From SwitchStatusValues
suspend fun isPreventUninstallSwitchOn(): Boolean =
    dao.get("prevent_uninstall_switch")?.asBoolean() ?: false
```

When toggled ON:
1. The boolean is persisted to `switch_status` table
2. `BlockerPageUtils.updateAccessibilityBlockingValues()` is called
3. The accessibility service's `refreshBlockingConfig()` updates `isPreventUninstallOn`
4. From now on, any window state change event from `com.android.settings` is checked against `isAppInfoPage()`

#### 2.2.6 Additional Anti-Uninstall: Block Notification Drawer + Block Recent Apps

These features prevent the user from using system UI to force-stop or uninstall the app:

**Block Notification Drawer:**
- Detects: `StatusBar`, `notification`, `quicksettings`, `shade` class names
- Action: `GLOBAL_ACTION_HOME` + block screen
- Purpose: Prevents accessing settings from the notification shade quick tiles

**Block Recent Apps:**
- Detects: `recents`, `recentapps`, `overview`, `taskview` class names
- Action: `GLOBAL_ACTION_HOME` + block screen
- Purpose: Prevents force-stopping or uninstalling from the recent apps screen

#### 2.2.7 Block Phone Reboot

Detects power menu / ultra power saving modes:

```kotlin
// From decompiled BlockerPageUtils
List<String> huwaiUltraPowerSavingToMatch() {
    return listOf(
        "Ultra battery saver",
        "초절전모드",           // Korean
        "סוללה חסכ'",           // Hebrew
        "ウルトラ 省電力",         // Japanese
        "Ultrabatería",         // Spanish
        "Ultra-Akku",           // German
        "Akkuopti",             // German (alternate)
        "Energiesparmodus",     // German
        "Gestion d'alim. Ultra", // French
        "Режим Ультра",         // Russian
        "ultra power saving",    // English
        "초절전",               // Korean (short)
        "ウルトラ省電力の",       // Japanese (alternate)
        "חיסכון גבוה במיוחד",    // Hebrew (alternate)
        "ahorro de energía ultra", // Spanish (alternate)
        "Ultra-Stromsparen",    // German (alternate)
        "gestion d'alimentation Ultra", // French (alternate)
        "you (owner)",          // Multi-user
        "Add user",             // Multi-user
        "Add guest"             // Multi-user
    );
}
```

This prevents the user from entering ultra power saving mode, which would kill the accessibility service.

### 2.3 Key Observations — Uninstall Protection

1. **Multi-layered defense**: The uninstall protection uses 4 independent layers — Device Admin, accessibility page detection, boot persistence, and self-heal. Each layer addresses a different attack vector.

2. **Device Admin as first barrier**: Device Admin is the simplest and most reliable barrier — the system enforces it at the OS level. Without deactivating Device Admin, the uninstall button is not accessible.

3. **Accessibility as second barrier**: Even if Device Admin is somehow bypassed, the accessibility service detects when the user navigates to the app info page and immediately dismisses it.

4. **Boot persistence ensures continuity**: After a reboot, the `BOOT_COMPLETED` receiver restarts all services. Without this, a user could simply reboot to kill the accessibility service.

5. **Self-heal addresses service disruption**: If the user manages to disable the accessibility service (via developer options, ADB, or another accessibility service conflict), the guard detects this within 30 seconds and prompts the user to re-enable it.

6. **Localization awareness**: The power saving detection includes localized strings for Korean, Hebrew, Japanese, Spanish, German, French, and Russian — ensuring the feature works across different device languages.

7. **Throttling**: Block activity launches are throttled to 500ms per package to prevent rapid-fire intents that could crash the app or system.

8. **HOME action before block**: The service always presses `GLOBAL_ACTION_HOME` before showing the block screen. This dismisses the offending app/page from view, making it harder for the user to interact with it.

9. **Premium-gated in original**: In the original NopoX, the Prevent Uninstall feature was premium-gated (`PremiumFeatureIdentifiers.PREVENT_UNINSTALL`). The rebuild makes it free.

10. **`isAccessibilityTool="true"`**: The manifest declares the app as an accessibility tool, which affects how the system manages the service and prevents some OEMs from auto-killing it.

---

## 3. Comparison: Original NopoX vs Rebuild

| Feature | Original NopoX | Rebuild (Protect Yourself) |
|---|---|---|
| Title-based blocking switch | Toggle switch | Text input field (user enters titles) |
| Title matching | `contains()` on event text | Same — `contains()` on event text |
| Keyword storage | `selected_keyword_table` with `setting_keywords_list_words` identifier | Same |
| Prevent uninstall | 4-layer system | Same 4-layer system |
| Device Admin | Empty `<uses-policies>` | Same — empty policies |
| Boot persistence | `AppSystemActionReceiverAllTime` | Same |
| Self-heal | `AccessibilityGuard` (30s polling) | Same |
| Block notification drawer | ✅ Included | ❌ Removed (per user request) |
| Block recent apps | ✅ Included | ❌ Removed (per user request) |
| Block phone reboot | ✅ Included (with localization) | ✅ Included |
| Premium gating | ✅ All features premium-gated | ❌ All features free |
| Signature killer | `bin.mt.signature.KillerApplication` | Removed (extends Application directly) |

---

## 4. Security Considerations

1. **Device Admin limitations**: On Android 14+, Device Admin for personal apps is deprecated. The feature may not work on newer devices. Enterprise-managed devices still support it.

2. **Accessibility service killing**: Some aggressive OEM battery optimizations (Xiaomi, Huawei, Samsung) can kill accessibility services. NopoX addresses this with the self-heal guard and OEM-specific autostart instructions.

3. **ADB bypass**: A user with ADB access can disable the accessibility service via `adb shell settings put secure enabled_accessibility_services ""`. The self-heal guard detects this but can only show a notification — it cannot re-enable the service programmatically on Android 13+.

4. **Safe mode**: Booting into safe mode disables all third-party apps, including the accessibility service. NopoX's boot receiver handles `BOOT_COMPLETED` but not safe mode specifically.

5. **Title matching false positives**: The substring matching (`contains()`) can cause false positives. For example, keyword "apps" would match any settings page containing "apps" in its title, which is many pages.

---

## 5. Files Analyzed

| File | Lines | Purpose |
|---|---|---|
| `MyAccessibilityService.java` | ~800 (decompiled) | Core blocking logic, event handling |
| `BlockerPageUtils.java` | 3,166 | URL/keyword matching, device brand detection, power saving text matching |
| `PornBlockPage.java` | ~400 | Block screen display, countdown, rating prompt |
| `DeviceAdminUtils.java` | ~100 | Device Admin activation/deactivation |
| `AccessibilityGuard.kt` | ~130 | Self-heal polling + notification |
| `AppSystemActionReceiverAllTime.kt` | ~80 | Boot/screen event receiver |
| `SwitchStatusValues.java` | ~300 (60+ getters) | All switch state accessors |
| `SettingPageItemIdentifiers.kt` | ~60 | Enum of all setting items |
| `AndroidManifest.xml` | 368 | All component declarations |
| `strings.xml` | 884 | All user-facing strings |
| `accessibility_setting.xml` | 8 | Accessibility service configuration |
| `device_admin.xml` | 3 | Device admin policies (empty) |

---

## Conclusion

The NopoX APK implements a sophisticated multi-layered protection system:

- **Title-based blocking** uses accessibility events to detect settings page titles and match them against user-defined keywords. The implementation is straightforward but effective — substring matching on the focused view's text.

- **Uninstall protection** combines Device Admin (OS-level barrier), accessibility-based page detection (app-level barrier), boot persistence (survives reboots), and self-heal monitoring (detects service disruption). This 4-layer approach makes it significantly harder for users to circumvent protection.

The rebuild (Protect Yourself) preserves the core architecture while removing premium gating, simplifying the UI, and adapting the title-based blocking to use a text input field instead of a keyword management page.
