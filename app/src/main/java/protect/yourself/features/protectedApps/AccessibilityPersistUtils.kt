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
 * service enablement, ported from the original reference implementation
 * (`AccessibilityPersistUtils`).
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
 * ## What's new vs the reference
 *
 *   - `protect.yourself` package instead of the reference app's package
 *   - Kotlin idiom instead of Java-flavoured Kotlin
 *   - `guardAllProtectedServices()` enumerates *all* installed accessibility
 *     services (via `PackageManager.getInstalledPackages`) so the user can
 *     choose to protect other accessibility apps too (e.g. a password
 *     manager's autofill service) — the reference only protected its own.
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
     * A11Y-PERSIST-04 (v1.0.69): context-safe variant of [ownComponentFlat].
     * The lazy val above dereferences PackageManagerProvider eagerly — if it
     * is touched before App.onCreate step 6 (e.g. very-early service connect),
     * it throws. This overload falls back to `context.packageName`, which
     * always resolves to the same value.
     */
    @JvmStatic
    fun ownComponentFlat(context: Context): String {
        val pkg = try {
            protect.yourself.commons.utils.PackageManagerProvider.getPackageName()
        } catch (_: Throwable) {
            context.applicationContext.packageName
        }
        return ComponentName(pkg, MyAccessibilityService::class.java.name).flattenToString()
    }

    /**
     * A11Y-PERSIST-02 (v1.0.69): structural equality for flat component
     * strings. Android (and OEMs) may store either the full form
     * `pkg/pkg.features.Svc` or the short form `pkg/.features.Svc` in
     * `enabled_accessibility_services`; exact-string matching false-negatives
     * on the alternate form. Compare via [ComponentName.unflattenFromString]
     * (which normalizes both forms) and fall back to exact equality on
     * unparseable input.
     */
    @JvmStatic
    fun componentEntriesMatch(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        if (a == b) return true
        return try {
            val ca = ComponentName.unflattenFromString(a) ?: return false
            val cb = ComponentName.unflattenFromString(b) ?: return false
            ca.packageName == cb.packageName && ca.className == cb.className
        } catch (_: Throwable) {
            false
        }
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
            // LinkedHashSet: dedupe + preserve list order (canonicalization)
            else raw.split(':').filter { it.isNotBlank() }.toCollection(LinkedHashSet())
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: failed to read enabled_accessibility_services")
            emptySet()
        }
    }

    /**
     * A11Y-PERSIST-03 (v1.0.69): state of the MASTER accessibility switch
     * (`Settings.Secure.ACCESSIBILITY_ENABLED`).
     *
     * Several OEM builds (notably MIUI/MIUI-optimization and some Knox policy
     * paths) flip this to 0 *while leaving our entry in the enabled list* —
     * the service then disconnects but every "is our entry present?" check
     * keeps reporting enabled, and self-heal never fires because the early
     * return considers the service fine. Blocking silently dies while the
     * Settings UI still shows the service ON: this is a real-world
     * "service disabled automatically" vector that the previous
     * implementation could neither detect nor repair.
     *
     * Defaults to 1 when the key is absent (framework default state).
     */
    @JvmStatic
    fun isAccessibilityMasterEnabled(context: Context): Boolean {
        return try {
            Settings.Secure.getInt(
                context.applicationContext.contentResolver,
                KEY_ACCESSIBILITY_ENABLED, 1
            ) == 1
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: failed to read accessibility_enabled — assuming enabled")
            true
        }
    }

    /**
     * True only when our service is BOTH listed AND the master switch is on
     * — i.e. blocking is actually functional. Used by the guard's polling
     * loop so master-switch flips trigger self-heal instead of being
     * mistaken for "all good".
     */
    @JvmStatic
    fun isAccessibilityEffectivelyEnabled(context: Context): Boolean {
        return isOwnServiceEnabled(context) && isAccessibilityMasterEnabled(context)
    }

    /**
     * Is our accessibility service currently in the enabled list?
     * A11Y-PERSIST-02: component-form tolerant (see [componentEntriesMatch]).
     */
    @JvmStatic
    fun isOwnServiceEnabled(context: Context): Boolean {
        val own = ownComponentFlat(context)
        return getEnabledServicesSet(context).any { componentEntriesMatch(it, own) }
    }

    /**
     * **Core self-heal method.** Repairs BOTH disable vectors:
     *   1. Our entry missing from `enabled_accessibility_services` → re-add.
     *   2. Master switch `accessibility_enabled=0` while our entry is present
     *      (OEM flip — A11Y-PERSIST-03) → re-enable the master switch.
     *
     * A11Y-PERSIST-01 (v1.0.69): the previous read-modify-write was
     * unsynchronized and duplicated entries whenever two callers raced
     * (`"A:B:B"`) — a malformed list that some AccessibilityManagers/Settings
     * providers handle by dropping the service entirely. This method is now
     * `@Synchronized` and performs a CANONICAL rewrite (deduped,
     * order-preserving LinkedHashSet + component-form matching), so racing
     * callers converge instead of corrupting the setting. A write only
     * happens when the value would actually change (no pointless churn that
     * could re-trigger our own ContentObserver).
     *
     * @return true if the service is (now) effectively enabled; false if it
     *   could not be repaired (permission missing, write failed, or an OEM
     *   SecurityException).
     */
    @JvmStatic
    @Synchronized
    fun selfHealAccessibilityService(context: Context): Boolean {
        val own = ownComponentFlat(context)
        val rawEntries = getEnabledServicesSet(context)
        val ownPresent = rawEntries.any { componentEntriesMatch(it, own) }
        val masterEnabled = isAccessibilityMasterEnabled(context)

        // Fast path: fully functional (entry present + master on).
        if (ownPresent && masterEnabled) return true

        // Need the permission to repair anything.
        if (!isWriteSecureSettingsGranted(context)) {
            Timber.d("$TAG: WRITE_SECURE_SETTINGS not granted — cannot self-heal (ownPresent=$ownPresent master=$masterEnabled)")
            return false
        }

        val cr: ContentResolver = try {
            context.applicationContext.contentResolver
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: no ContentResolver")
            return false
        }

        return try {
            // A11Y-PERSIST-01: canonicalize (dedupe by component identity,
            // ORIGINAL ORDER PRESERVED) and only write when something
            // actually changes — an order-preserving parse means a healthy
            // list round-trips byte-identical and no write churn occurs.
            val canonical = LinkedHashSet<String>()
            val rawString = try {
                Settings.Secure.getString(cr, KEY_ENABLED_SERVICES) ?: ""
            } catch (_: Throwable) { "" }
            var hadDuplicates = false
            for (entry in rawString.split(':')) {
                if (entry.isBlank()) continue
                when {
                    // Normalize our entry (either storage form) to the full form.
                    componentEntriesMatch(entry, own) ->
                        if (canonical.none { componentEntriesMatch(it, own) }) canonical.add(own)
                        else hadDuplicates = true
                    canonical.any { componentEntriesMatch(it, entry) } -> hadDuplicates = true
                    else -> canonical.add(entry)
                }
            }
            // Our entry was missing → append it once, at the end.
            if (canonical.none { componentEntriesMatch(it, own) }) canonical.add(own)

            val newValue = canonical.joinToString(":")
            val needsListWrite = !ownPresent || hadDuplicates || newValue != rawString

            var listOk = true
            if (needsListWrite) {
                listOk = Settings.Secure.putString(cr, KEY_ENABLED_SERVICES, newValue)
                if (listOk) {
                    Timber.i("$TAG: self-heal repaired enabled list (addedOwn=${!ownPresent} deduped=$hadDuplicates)")
                } else {
                    Timber.w("$TAG: putString returned false (OEM blocked the write?)")
                }
            }

            // A11Y-PERSIST-03: repair the master switch whenever we can write
            // (even if the service list itself was already fine).
            var masterOk = true
            if (!masterEnabled) {
                masterOk = Settings.Secure.putInt(cr, KEY_ACCESSIBILITY_ENABLED, 1)
                Timber.i("$TAG: master accessibility switch re-enabled (previous=$masterEnabled)")
            }

            if (listOk && (needsListWrite || !masterEnabled)) {
                // Breadcrumb is diagnostics-only — it must NEVER flip the
                // result to false after a successful repair (CrashLogger is
                // backed by Room, which is unavailable early in process start
                // and in unit tests).
                try {
                    protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                        "A11ySelfHeal",
                        "repaired: addedOwn=${!ownPresent} deduped=$hadDuplicates masterFixed=${!masterEnabled}"
                    )
                } catch (_: Throwable) {}
            }
            listOk && masterOk
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
     * has chosen to protect via [ProtectedAppsRegistry]). This mirrors the
     * reference's `AccessibilityGuard.guardAll()`.
     *
     * Only call this when [isWriteSecureSettingsGranted] is true — otherwise
     * the writes silently no-op (wasted work).
     *
     * A11Y-PERSIST-01 (v1.0.69): `@Synchronized` + canonical union write
     * (same malformed-string corruption class as the main self-heal path).
     * Also: the master switch is now only written when the list write
     * actually succeeds (previously written unconditionally — pointless and
     * observable by the system as setting churn).
     */
    @JvmStatic
    @Synchronized
    fun guardAllProtectedServices(context: Context) {
        if (!isWriteSecureSettingsGranted(context)) return

        try {
            val cr = context.applicationContext.contentResolver
            val current = getEnabledServicesSet(context).toMutableSet()  // already deduped

            val toAdd = ProtectedAppsRegistry.getComponents(context)
                .filter { cmp -> cmp.isNotBlank() && current.none { componentEntriesMatch(it, cmp) } }
            if (toAdd.isEmpty()) return

            current.addAll(toAdd)
            val ok = Settings.Secure.putString(cr, KEY_ENABLED_SERVICES, current.joinToString(":"))
            if (ok) {
                Settings.Secure.putInt(cr, KEY_ACCESSIBILITY_ENABLED, 1)
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
 * Ported from the reference `ProtectedAppsRegistry.kt`.
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
}
