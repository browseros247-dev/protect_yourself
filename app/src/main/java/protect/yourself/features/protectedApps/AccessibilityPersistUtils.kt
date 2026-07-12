package protect.yourself.features.protectedApps

import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import timber.log.Timber

/**
 * AccessibilityPersistUtils — programmatic persistence of the accessibility
 * service enablement, ported from the original NopoX implementation
 * (`com.planproductive.nopox.features.blockerPage.utils.AccessibilityPersistUtils`).
 *
 * ## How it works
 *
 * If (and only if) the `android.permission.WRITE_SECURE_SETTINGS` permission
 * has been granted to this app (typically via `adb shell pm grant`), this
 * module is able to **write directly to `Settings.Secure`** to:
 *
 *   1. Append our service component to `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`
 *      (a colon-separated list of `pkg/svc` flat ComponentNames).
 *   2. Set `Settings.Secure.ACCESSIBILITY_ENABLED = 1`.
 *
 * This bypasses the standard "user must enable accessibility manually"
 * restriction of Android 13+, allowing the app to **self-heal** its own
 * accessibility service the moment Android removes it (which OEMs do
 * aggressively — Xiaomi/Huawei/Samsung kill background accessibility
 * services every 12h–48h for battery optimisation).
 *
 * ## Permission requirement
 *
 * `WRITE_SECURE_SETTINGS` has `protectionLevel="signature|privileged|development"`,
 * meaning it can only be granted:
 *   - to system-signed apps (not us), OR
 *   - to apps signed with the platform key (not us), OR
 *   - via ADB (`adb shell pm grant protect.yourself android.permission.WRITE_SECURE_SETTINGS`).
 *
 * We cannot request it at runtime. The app must therefore **guide the user
 * through the ADB grant** (see `WriteSecureSettingsSetupPage`).
 *
 * ## Safety
 *
 * All write paths are wrapped in try/catch — if the permission is missing or
 * the write fails (some OEMs block `putString` even with the permission), the
 * method simply returns false and the caller falls back to the notification-
 * based "please re-enable manually" flow.
 *
 * ## What's new vs NopoX
 *
 *   - `protect.yourself` package instead of `com.planproductive.nopox`
 *   - Kotlin idiom instead of Java-flavoured Kotlin
 *   - `guardAllProtectedServices()` enumerates *all* installed accessibility
 *     services (via `PackageManager.getInstalledPackages`) so the user can
 *     choose to protect other accessibility apps too (e.g. a password
 *     manager's autofill service) — NopoX only protected its own.
 *   - Public `isGranted` property for UI binding
 *   - Public `getEnabledServicesList()` for diagnostics + UI
 */
object AccessibilityPersistUtils {

    private const val TAG = "A11yPersist"

    private val KEY_ENABLED_SERVICES = Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    private const val KEY_ACCESSIBILITY_ENABLED = "accessibility_enabled"
    /**
     * The flat ComponentName of our accessibility service, e.g.
     * `"protect.yourself/protect.yourself.features.blockerPage.service.MyAccessibilityService"`.
     * This is the exact string Android stores in `enabled_accessibility_services`.
     */
    @JvmStatic
    val ownComponentFlat: String by lazy {
        ComponentName(
            protect.yourself.commons.utils.PackageManagerProvider.getPackageName(),
            MyAccessibilityService::class.java.name
        ).flattenToString()
    }

    /**
     * Has the user granted us `WRITE_SECURE_SETTINGS` via ADB?
     *
     * NB: `ContextCompat.checkSelfPermission` returns `PERMISSION_GRANTED` (0)
     * for system-granted permissions. For `WRITE_SECURE_SETTINGS`, this only
     * returns 0 if `pm grant` has been run — it does NOT return 0 merely
     * because the permission is declared in the manifest.
     */
    @JvmStatic
    fun isWriteSecureSettingsGranted(context: Context): Boolean {
        return try {
            ContextCompat.checkSelfPermission(
                context.applicationContext,
                "android.permission.WRITE_SECURE_SETTINGS"
            ) == PackageManager.PERMISSION_GRANTED
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: failed to check WRITE_SECURE_SETTINGS")
            false
        }
    }

    /**
     * Read the current `enabled_accessibility_services` setting as a set of
     * flat ComponentName strings. Empty set if the setting is unset.
     */
    @JvmStatic
    fun getEnabledServicesSet(context: Context): Set<String> {
        return try {
            val raw = Settings.Secure.getString(
                context.applicationContext.contentResolver,
                KEY_ENABLED_SERVICES
            ) ?: return emptySet()
            if (raw.isBlank()) emptySet()
            else raw.split(':').filter { it.isNotBlank() }.toSet()
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: failed to read enabled_accessibility_services")
            emptySet()
        }
    }

    /**
     * Is our accessibility service currently in the enabled list?
     */
    @JvmStatic
    fun isOwnServiceEnabled(context: Context): Boolean {
        return getEnabledServicesSet(context).contains(ownComponentFlat)
    }

    /**
     * **Core self-heal method.** If `WRITE_SECURE_SETTINGS` is granted and
     * our service is missing from `enabled_accessibility_services`, append
     * it and set `accessibility_enabled=1`.
     *
     * @return true if the service is (now) enabled; false if it could not be
     *   re-armed (permission missing, write failed, or a SecurityException
     *   was thrown by an OEM that blocks `putString` even with the permission).
     */
    @JvmStatic
    fun selfHealAccessibilityService(context: Context): Boolean {
        // Fast path: already enabled.
        if (isOwnServiceEnabled(context)) return true

        // Slow path: need the permission to re-arm.
        if (!isWriteSecureSettingsGranted(context)) {
            Timber.d("$TAG: WRITE_SECURE_SETTINGS not granted — cannot self-heal")
            return false
        }

        val cr: ContentResolver = try {
            context.applicationContext.contentResolver
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: no ContentResolver")
            return false
        }

        return try {
            val current = Settings.Secure.getString(cr, KEY_ENABLED_SERVICES) ?: ""
            val newValue = if (current.isBlank()) {
                ownComponentFlat
            } else {
                // Append — preserve any existing services the user has enabled.
                "$current:$ownComponentFlat"
            }
            val ok = Settings.Secure.putString(cr, KEY_ENABLED_SERVICES, newValue)
            if (ok) {
                // Also flip the master switch. Some OEMs (MIUI) require both
                // entries to be present or the service won't actually run.
                Settings.Secure.putInt(cr, KEY_ACCESSIBILITY_ENABLED, 1)
                Timber.i("$TAG: self-heal succeeded — service re-added to enabled list")
                true
            } else {
                Timber.w("$TAG: putString returned false (OEM blocked the write?)")
                false
            }
        } catch (se: SecurityException) {
            // Some ROMs throw SecurityException even when the permission is granted.
            Timber.w(se, "$TAG: SecurityException during self-heal")
            false
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: self-heal failed")
            false
        }
    }

    /**
     * Re-arm **all** protected accessibility services (our own + any the user
     * has chosen to protect via [ProtectedAppsRegistry]). This mirrors NopoX's
     * `AccessibilityGuard.guardAll()`.
     *
     * Only call this when [isWriteSecureSettingsGranted] is true — otherwise
     * the writes silently no-op (wasted work).
     */
    @JvmStatic
    fun guardAllProtectedServices(context: Context) {
        if (!isWriteSecureSettingsGranted(context)) return

        try {
            val cr = context.applicationContext.contentResolver
            val current = Settings.Secure.getString(cr, KEY_ENABLED_SERVICES) ?: ""
            val currentSet = if (current.isBlank()) emptySet()
                             else current.split(':').filter { it.isNotBlank() }.toHashSet()

            val toAdd = ProtectedAppsRegistry.getComponents(context)
                .filter { it.isNotBlank() && !currentSet.contains(it) }
            if (toAdd.isEmpty()) return

            val sb = StringBuilder(current)
            for (cmp in toAdd) {
                if (sb.isNotEmpty()) sb.append(':')
                sb.append(cmp)
            }
            val ok = Settings.Secure.putString(cr, KEY_ENABLED_SERVICES, sb.toString())
            Settings.Secure.putInt(cr, KEY_ACCESSIBILITY_ENABLED, 1)
            if (ok) {
                Timber.i("$TAG: guardAll re-added ${toAdd.size} protected service(s)")
            } else {
                Timber.w("$TAG: guardAll putString returned false")
            }
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: guardAll failed")
        }
    }

    /**
     * **Safe entry point.** Called from:
     *   - `ProtectYourselfApp.onCreate()` at app start
     *   - `MainActivity.onStart()` when the app comes to the foreground
     *   - `MyAccessibilityService.onServiceConnected()` when the service starts
     *   - `MyAccessibilityService.onUnbind()` when Android unbinds (may be the
     *     precursor to disabling the service)
     *   - `AppSystemActionReceiverAllTime.onReceive()` on boot / screen-on
     *
     * Idempotent. Performs no work if the permission is missing.
     */
    @JvmStatic
    fun selfHealSafe(context: Context) {
        try {
            selfHealAccessibilityService(context)
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: selfHealAccessibilityService threw")
        }
        try {
            guardAllProtectedServices(context)
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: guardAllProtectedServices threw")
        }
        try {
            // Re-arm the ContentObserver-based watcher so we get notified the
            // instant Android removes us from the enabled list (much faster
            // than the 30-second polling loop).
            AccessibilityGuard.getInstance().ensureWatching(context)
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: ensureWatching threw")
        }
    }

    /**
     * Convenience overload for callers that don't have a Context handy —
     * uses the PackageManagerProvider's application context. Kept for
     * source-compatibility with the stub's `selfHealSafe()` signature.
     */
    fun selfHealSafe() {
        try {
            val ctx = protect.yourself.commons.utils.PackageManagerProvider.getApplicationContext()
                ?: return
            selfHealSafe(ctx)
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: selfHealSafe() fallback threw")
        }
    }

    /**
     * Enumerate every installed accessibility service (across all apps),
     * returning a list of `(flatComponent, appLabel, isOurs)` tuples.
     *
     * Used by `ProtectedAppsActivity` to render the protection toggle list.
     */
    @JvmStatic
    fun listAllAccessibilityServices(context: Context): List<ProtectedServiceEntry> {
        val pm = context.packageManager
        val ownPkg = context.packageName
        val out = ArrayList<ProtectedServiceEntry>()
        try {
            val installed = pm.getInstalledPackages(PackageManager.GET_SERVICES)
            for (pkg in installed) {
                val services = pkg.services ?: continue
                for (svc in services) {
                    if (svc.permission != "android.permission.BIND_ACCESSIBILITY_SERVICE") continue
                    val flat = pkg.packageName + "/" + svc.name
                    val appInfo = pkg.applicationInfo
                    val label = try {
                        if (appInfo != null) pm.getApplicationLabel(appInfo).toString()
                        else pkg.packageName
                    } catch (_: Throwable) {
                        pkg.packageName
                    }
                    val isOurs = ownPkg == pkg.packageName
                    val icon = try {
                        if (appInfo != null) pm.getApplicationIcon(appInfo) else null
                    } catch (_: Throwable) { null }
                    out.add(
                        ProtectedServiceEntry(
                            flatComponent = flat,
                            appLabel = label,
                            serviceClass = svc.name,
                            isOurs = isOurs,
                            icon = icon
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: listAllAccessibilityServices failed")
        }
        // Sort: our service first, then alphabetical
        out.sortWith(compareByDescending<ProtectedServiceEntry> { it.isOurs }.thenBy { it.appLabel.lowercase() })
        return out
    }
}

/**
 * One row in the protected-apps list.
 */
data class ProtectedServiceEntry(
    val flatComponent: String,
    val appLabel: String,
    val serviceClass: String,
    val isOurs: Boolean,
    val icon: android.graphics.drawable.Drawable?
)

/**
 * Persistent registry of which third-party accessibility services the user
 * wants to protect (in addition to our own, which is always protected).
 *
 * Stored in a SharedPreferences file so it survives app reinstalls
 * (as long as `android:allowBackup` allows — currently false, but the file
 * is preserved across app updates).
 *
 * Ported from NopoX `ProtectedAppsRegistry.kt`.
 */
object ProtectedAppsRegistry {
    private const val PREFS_NAME = "protect_yourself_protected_apps"
    private const val KEY_COMPONENTS = "components"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, 0)

    @JvmStatic
    fun getComponents(context: Context): Set<String> = try {
        prefs(context).getStringSet(KEY_COMPONENTS, emptySet()) ?: emptySet()
    } catch (_: Throwable) { emptySet() }

    @JvmStatic
    fun contains(context: Context, flat: String): Boolean =
        flat.isNotBlank() && getComponents(context).contains(flat)

    @JvmStatic
    fun add(context: Context, flat: String): Boolean {
        if (flat.isBlank()) return false
        val current = LinkedHashSet(getComponents(context))
        val added = current.add(flat)
        if (added) prefs(context).edit().putStringSet(KEY_COMPONENTS, current).apply()
        return added
    }

    @JvmStatic
    fun remove(context: Context, flat: String): Boolean {
        if (flat.isBlank()) return false
        val current = LinkedHashSet(getComponents(context))
        val removed = current.remove(flat)
        if (removed) prefs(context).edit().putStringSet(KEY_COMPONENTS, current).apply()
        return removed
    }

    @JvmStatic
    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_COMPONENTS).apply()
    }
}
