package protect.yourself.commons.utils.broadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.launch
import protect.yourself.core.appCoroutineScope
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.utils.NewInstallBlockingUtils
import timber.log.Timber

/**
 * Receiver for package install/remove/replace events.
 *
 * Handles:
 *  - MY_PACKAGE_REPLACED: App was updated — restart services
 *  - PACKAGE_ADDED: New app installed — auto-add to block list if "Block new
 *    install apps" is ON AND the package is a genuine first install (not an
 *    update — verified via [NewInstallBlockingUtils.isFirstInstall]).
 *  - PACKAGE_REMOVED: App uninstalled — clean up from DB
 *
 * # Bugs fixed (vs. the previous implementation)
 *
 *  1. **Missing `isFirstInstall` check.** Previously, ANY `PACKAGE_ADDED`
 *     broadcast (including app updates) would add the package to the new
 *     install block list. Now we verify the package is a genuine fresh
 *     install, matching the NopoX_1.0.53.apk reference implementation
 *     (`DeviceAppDataUtil.isFirstInstall`).
 *
 *  2. **No pre-insert cleanup.** Previously, if a row already existed for
 *     the package (e.g. from a previous install), the new insert would
 *     REPLACE it — but stale rows under a DIFFERENT identifier (e.g. the
 *     regular blocklist) would remain. Now we clean up the
 *     `BLOCK_NEW_INSTALL_APPS` identifier for the package before inserting,
 *     matching NopoX's `appInstallRemoveCallback` pattern.
 *
 *  3. **Silent failure when accessibility service is not connected.**
 *     Previously, `MyAccessibilityService.instance?.refreshBlockingConfig()`
 *     would silently no-op if the service wasn't connected. The new app
 *     would be in the DB but the cache wouldn't be refreshed until the next
 *     periodic worker (24h). Now we log a warning so the issue is visible
 *     in diagnostics, and the next `onServiceConnected` will pick it up.
 *
 *  4. **No comprehensive logging.** Previously, the only logs were
 *     "Package event: ..." and "New app auto-blocked: ...". Now we log the
 *     switch state, the first-install check result, the insert result, and
 *     the service-connection state at each step — making it possible to
 *     debug "blocking not working" reports from crash logs.
 *
 *  5. **Used `schemeSpecificPart` instead of `encodedSchemeSpecificPart`.**
 *     For most package names these are identical, but for package names
 *     containing URL-reserved characters they differ. Now we use the
 *     encoded form via [NewInstallBlockingUtils.extractPackageName] to match
 *     NopoX exactly.
 *
 *  6. **No error handling around the DB write.** Previously, a DB exception
 *     would bubble up to the outer try/catch and be logged generically. Now
 *     each step has its own try/catch with specific error context, and we
 *     verify the insert succeeded by reading the row back.
 *
 *  7. **`MY_PACKAGE_REPLACED` was filtered out by the `<data scheme="package">`
 *     in the manifest.** That manifest bug is fixed separately; the receiver
 *     code now also handles `MY_PACKAGE_REPLACED` defensively (refreshes
 *     service config) even if the data URI is absent.
 */
class AppSystemActionReceiverAllTimeWithData : BroadcastReceiver() {

    private val scope = appCoroutineScope(
        scopeName = "AppSystemActionReceiverAllTimeWithData",
        dispatcher = kotlinx.coroutines.Dispatchers.IO
    )

    override fun onReceive(context: Context, intent: Intent) {
        Timber.i("Package event: action=${intent.action} data=${intent.data}")
        // Mirror NopoX: attempt self-heal on every package event. The most
        // important one is MY_PACKAGE_REPLACED — after an app update, Android
        // sometimes re-evaluates accessibility permissions and may disable
        // the service. selfHealSafe re-arms it instantly.
        try {
            protect.yourself.features.protectedApps.AccessibilityPersistUtils.selfHealSafe(context)
        } catch (t: Throwable) {
            Timber.w(t, "selfHealSafe in package-event receiver failed")
        }
        val pendingResult = goAsync()

        scope.launch {
            try {
                val packageName = NewInstallBlockingUtils.extractPackageName(intent.data)
                when (intent.action) {
                    Intent.ACTION_MY_PACKAGE_REPLACED -> {
                        // App was updated — refresh accessibility config.
                        // Note: MY_PACKAGE_REPLACED has NO data URI, so
                        // `packageName` will be null here. That's expected —
                        // we refresh the whole config, not just one package.
                        Timber.i("App replaced — refreshing accessibility config")
                        refreshServiceConfig(context, "MY_PACKAGE_REPLACED")
                    }

                    Intent.ACTION_PACKAGE_ADDED -> {
                        if (packageName.isNullOrBlank()) {
                            Timber.w("PACKAGE_ADDED with null/blank package — skipping")
                            return@launch
                        }
                        handlePackageAdded(context, packageName)
                    }

                    Intent.ACTION_PACKAGE_REMOVED -> {
                        if (packageName.isNullOrBlank()) {
                            Timber.w("PACKAGE_REMOVED with null/blank package — skipping")
                            return@launch
                        }
                        handlePackageRemoved(context, packageName)
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to handle package event: ${intent.action}")
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Handles `ACTION_PACKAGE_ADDED`.
     *
     * Flow:
     *  1. Check if "Block new install apps" switch is ON.
     *  2. If ON, verify the package is a genuine first install (not an
     *     update) via [NewInstallBlockingUtils.isFirstInstall].
     *  3. If it's a first install:
     *     a. Clean up any existing row for this package under the
     *        BLOCK_NEW_INSTALL_APPS identifier (defensive — matches NopoX's
     *        `appInstallRemoveCallback` pattern).
     *     b. Insert the new row with `isSelected = true`.
     *     c. Verify the insert succeeded by reading the row back.
     *     d. Refresh the accessibility service's cached config so the new
     *        app is blocked immediately (not on the next periodic refresh).
     */
    private suspend fun handlePackageAdded(context: Context, packageName: String) {
        val db = try {
            AppDatabase.getInstance(context)
        } catch (t: Throwable) {
            Timber.e(t, "handlePackageAdded: failed to get DB instance — cannot process pkg=$packageName")
            return
        }

        val switchValues = SwitchStatusValues(db.switchStatusDao())
        val switchOn = try {
            switchValues.isBlockNewInstallAppsSwitchOn()
        } catch (t: Throwable) {
            Timber.e(t, "handlePackageAdded: failed to read switch state — assuming OFF for pkg=$packageName")
            false
        }

        Timber.i(
            "handlePackageAdded: pkg=$packageName blockNewInstallSwitch=$switchOn " +
                "serviceConnected=${MyAccessibilityService.instance != null}"
        )

        if (!switchOn) {
            Timber.v("handlePackageAdded: switch is OFF — not adding pkg=$packageName")
            return
        }

        // Verify this is a genuine first install, not an update.
        // NopoX uses DeviceAppDataUtil.isFirstInstall for this — we port
        // that logic in NewInstallBlockingUtils.
        val isFirstInstall = try {
            NewInstallBlockingUtils.isFirstInstall(context, packageName)
        } catch (t: Throwable) {
            Timber.e(t, "handlePackageAdded: isFirstInstall threw — assuming NOT first install for pkg=$packageName")
            false
        }

        if (!isFirstInstall) {
            Timber.i(
                "handlePackageAdded: pkg=$packageName is NOT a first install " +
                    "(likely an update) — not adding to block list"
            )
            // Even though we're not adding, refresh the config in case the
            // update changed the app's package name or other metadata.
            refreshServiceConfig(context, "PACKAGE_ADDED (update)")
            return
        }

        // Resolve the app name for display in the blocklist UI.
        val appName = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (t: Throwable) {
            Timber.w(t, "handlePackageAdded: failed to resolve app name for pkg=$packageName — using package name")
            packageName
        }

        // Step a: clean up any existing row for this package under the
        // BLOCK_NEW_INSTALL_APPS identifier. This handles the edge case
        // where the user previously installed → uninstalled → reinstalled
        // the same app. Without this, the old row (with possibly stale
        // appName) would be REPLACEd, but we want a clean state.
        try {
            db.selectedAppsListDao().deleteByIdentifierAndPackage(
                SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                packageName
            )
        } catch (t: Throwable) {
            Timber.w(t, "handlePackageAdded: pre-insert cleanup failed for pkg=$packageName (non-fatal — will REPLACE)")
        }

        // Step b: insert the new row.
        val item = SelectedAppItemModel(
            key = "block_new_install_$packageName",
            packageName = packageName,
            appName = appName,
            identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
            isSelected = true
        )
        try {
            db.selectedAppsListDao().upsert(item)
            Timber.i("handlePackageAdded: inserted pkg=$packageName appName='$appName' into BLOCK_NEW_INSTALL_APPS")
        } catch (t: Throwable) {
            Timber.e(t, "handlePackageAdded: DB insert FAILED for pkg=$packageName — app will NOT be blocked")
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logThrowable(
                throwable = t,
                tag = "NewInstallBlocking",
                message = "DB insert failed for pkg=$packageName — new install will not be blocked",
                extraContext = mapOf(
                    "packageName" to packageName,
                    "appName" to appName,
                    "switchOn" to switchOn.toString()
                )
            )
            return
        }

        // Step c: verify the insert succeeded by reading the row back.
        // This catches edge cases like DB corruption, disk full, or
        // transaction rollback that silently drop the insert.
        try {
            val rows = db.selectedAppsListDao()
                .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
            val found = rows.any { it.packageName == packageName }
            if (!found) {
                Timber.e(
                    "handlePackageAdded: VERIFICATION FAILED — row not found after insert for pkg=$packageName. " +
                        "Total rows in list: ${rows.size}. The app will NOT be blocked."
                )
                protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                    "NewInstallBlockingVerifyFail",
                    "pkg=$packageName was inserted but not found in subsequent read — possible DB corruption"
                )
                return
            }
            Timber.v("handlePackageAdded: verified pkg=$packageName is in BLOCK_NEW_INSTALL_APPS (${rows.size} total)")
        } catch (t: Throwable) {
            // Verification failure is non-fatal — the insert may have
            // succeeded. Log and continue to refresh.
            Timber.w(t, "handlePackageAdded: verification read failed for pkg=$packageName (non-fatal)")
        }

        // Step d: refresh the accessibility service's cached config so the
        // new app is blocked immediately. If the service isn't connected,
        // log a warning — the next onServiceConnected will pick it up.
        refreshServiceConfig(context, "PACKAGE_ADDED (new install: $packageName)")

        Timber.i("handlePackageAdded: SUCCESS — pkg=$packageName ($appName) auto-blocked")
    }

    /**
     * Handles `ACTION_PACKAGE_REMOVED`.
     *
     * Cleans up the package from ALL selected-apps lists (blocklist, new
     * install list, whitelist, etc.) to prevent stale entries.
     */
    private suspend fun handlePackageRemoved(context: Context, packageName: String) {
        val db = try {
            AppDatabase.getInstance(context)
        } catch (t: Throwable) {
            Timber.e(t, "handlePackageRemoved: failed to get DB instance — cannot clean up pkg=$packageName")
            return
        }

        var cleanedCount = 0
        for (identifier in SelectedAppListIdentifier.values()) {
            try {
                db.selectedAppsListDao()
                    .deleteByIdentifierAndPackage(identifier.value, packageName)
                cleanedCount++
            } catch (t: Throwable) {
                Timber.w(t, "handlePackageRemoved: failed to clean identifier=${identifier.value} for pkg=$packageName")
            }
        }
        Timber.i("handlePackageRemoved: cleaned pkg=$packageName from $cleanedCount/${SelectedAppListIdentifier.values().size} lists")

        // Refresh the service config so it stops blocking the removed app.
        refreshServiceConfig(context, "PACKAGE_REMOVED: $packageName")
    }

    /**
     * Refreshes the accessibility service's cached blocking config.
     *
     * If the service is not connected (`MyAccessibilityService.instance` is
     * null), logs a warning so the issue is visible in diagnostics. The
     * next `onServiceConnected` will call `refreshBlockingConfig()`, so the
     * change will eventually be picked up — but there may be a window where
     * the cache is stale.
     */
    private fun refreshServiceConfig(context: Context, reason: String) {
        val instance = MyAccessibilityService.instance
        if (instance == null) {
            Timber.w(
                "refreshServiceConfig: MyAccessibilityService.instance is null — " +
                    "config not refreshed (reason=$reason). " +
                    "The change will be picked up on next service connect."
            )
            protect.yourself.core.ProtectYourselfApp.getCrashLogger()?.logBreadcrumb(
                "NewInstallBlockingServiceDown",
                "Accessibility service not connected — config not refreshed (reason=$reason)"
            )
            return
        }
        try {
            instance.refreshBlockingConfig()
            Timber.v("refreshServiceConfig: refresh triggered (reason=$reason)")
        } catch (t: Throwable) {
            Timber.e(t, "refreshServiceConfig: refreshBlockingConfig threw (reason=$reason)")
        }
    }
}
