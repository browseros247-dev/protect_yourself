package protect.yourself.features.blockerPage.utils

import android.content.Context
import android.content.pm.PackageManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * NewInstallBlockingUtils — utilities for the "Block new install apps" feature.
 *
 * # Why this exists
 *
 * The original implementation in [AppSystemActionReceiverAllTimeWithData] had
 * several bugs that prevented the feature from working correctly:
 *
 *  1. **No first-install verification.** The receiver fired on EVERY
 *     `ACTION_PACKAGE_ADDED` broadcast, including app updates (where
 *     `firstInstallTime != lastUpdateTime`). This caused updates to be
 *     incorrectly added to the block list, polluting the user's blocklist
 *     with apps they had previously unblocked.
 *
 *  2. **Missing reference parity.** The reference implementation
 *     (`reference_1.0.53.apk → DeviceAppDataUtil.isFirstInstall`) performs a
 *     two-step check: `firstInstallTime == lastUpdateTime` (true only for
 *     fresh installs, not updates) AND the install happened within the last
 *     hour (guards against delayed broadcasts re-adding long-installed apps).
 *
 * This utility ports the reference's `isFirstInstall` logic faithfully, with these
 * improvements over the decompiled source:
 *
 *  - Uses `java.util.concurrent.TimeUnit` instead of Joda-Time (avoids a
 *    600 KB dependency for a single call site).
 *  - Adds structured logging at each decision branch for diagnostics.
 *  - Handles `SecurityException` (thrown by some OEMs when querying packages
 *    with `QUERY_ALL_PACKAGES` not granted) — the reference only catches
 *    `NameNotFoundException`.
 *  - Returns `true` on `NameNotFoundException` to match the reference (the package
 *    was already uninstalled by the time we checked, so we treat it as a
 *    fresh install that was immediately removed — harmless because the
 *    insert will be cleaned up by `ACTION_PACKAGE_REMOVED`).
 *
 * # Decision matrix
 *
 * | Condition                                    | Returns | Reason                                  |
 * |----------------------------------------------|---------|-----------------------------------------|
 * | `firstInstallTime == lastUpdateTime`         |         |                                         |
 * |   AND installed within last hour             | `true`  | Genuine fresh install                   |
 * | `firstInstallTime == lastUpdateTime`         |         |                                         |
 * |   AND installed > 1 hour ago                 | `false` | Delayed broadcast — don't re-add        |
 * | `firstInstallTime != lastUpdateTime`         | `false` | App was updated, not freshly installed  |
 * | `NameNotFoundException`                      | `true`  | Package gone — match reference behaviour    |
 * | Other exception                              | `false` | Be safe — don't block on unknown state  |
 *
 * Usage:
 * ```
 * if (NewInstallBlockingUtils.isFirstInstall(context, packageName)) {
 *     // Add to block list
 * }
 * ```
 */
object NewInstallBlockingUtils {

    /**
     * Maximum age (in hours) for a package to be considered a "fresh install".
     *
     * Matches the reference's `DeviceAppDataUtil.isFirstInstall` which uses
     * `new Interval(firstInstallTime, now).toDuration().getStandardHours() <= 1`.
     *
     * Why 1 hour: PACKAGE_ADDED broadcasts can be delayed by Doze mode, battery
     * saver, or OEM background restrictions. A 1-hour window is generous enough
     * to handle typical delays but short enough to avoid re-adding apps that
     * were installed long ago (e.g., if the user clears app data and the
     * broadcast is re-sent).
     */
    const val FRESH_INSTALL_WINDOW_HOURS = 1L

    /**
     * Determines whether the given package was freshly installed (not updated).
     *
     * Mirrors `com.planproductive.commons.utils.DeviceAppDataUtil.isFirstInstall`
     * from the reference APK.
     *
     * @param context any context (application or activity)
     * @param packageName the package name to check
     * @return `true` if the package is a fresh install within the last hour,
     *   `false` if it's an update, a stale install, or the state is unknown
     */
    fun isFirstInstall(context: Context, packageName: String): Boolean {
        if (packageName.isBlank()) {
            Timber.w("NewInstallBlockingUtils.isFirstInstall: blank package name — returning false")
            return false
        }

        // Don't treat our own package as a "new install" — we never want to
        // block ourselves. (Defensive: the receiver already skips our package,
        // but this guards against direct calls from other sites.)
        if (packageName == context.packageName) {
            Timber.v("NewInstallBlockingUtils.isFirstInstall: own package — returning false")
            return false
        }

        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(packageName, 0)
            val firstInstallTime = info.firstInstallTime
            val lastUpdateTime = info.lastUpdateTime
            val now = System.currentTimeMillis()

            Timber.v(
                "isFirstInstall: pkg=$packageName firstInstall=$firstInstallTime " +
                    "lastUpdate=$lastUpdateTime now=$now"
            )

            // Step 1: A fresh install has firstInstallTime == lastUpdateTime.
            // When an app is updated, lastUpdateTime advances while
            // firstInstallTime stays the same.
            if (firstInstallTime != lastUpdateTime) {
                Timber.v(
                    "isFirstInstall: pkg=$packageName is an update " +
                        "(firstInstall != lastUpdate) — returning false"
                )
                return false
            }

            // Step 2: The install must have happened within the last hour.
            // This guards against delayed broadcasts re-adding apps that were
            // installed long ago (e.g. after a device reboot, the system may
            // re-broadcast PACKAGE_ADDED for recently-installed apps).
            val hoursSinceInstall = TimeUnit.MILLISECONDS.toHours(now - firstInstallTime)
            val isFresh = hoursSinceInstall <= FRESH_INSTALL_WINDOW_HOURS

            Timber.i(
                "isFirstInstall: pkg=$packageName hoursSinceInstall=$hoursSinceInstall " +
                    "isFresh=$isFresh"
            )
            isFresh
        } catch (e: PackageManager.NameNotFoundException) {
            // Match the reference: return true. The package was already uninstalled
            // (race with PACKAGE_REMOVED), so treating it as a first install
            // is harmless — the insert will be cleaned up by the REMOVE handler.
            Timber.w(
                "isFirstInstall: pkg=$packageName not found (already removed?) — " +
                    "returning true to match reference behaviour"
            )
            true
        } catch (e: SecurityException) {
            // Some OEMs throw SecurityException if QUERY_ALL_PACKAGES isn't
            // granted. Be safe — don't block on unknown state.
            Timber.e(
                e,
                "isFirstInstall: SecurityException for pkg=$packageName — " +
                    "returning false (cannot verify install state)"
            )
            false
        } catch (t: Throwable) {
            Timber.e(
                t,
                "isFirstInstall: unexpected error for pkg=$packageName — " +
                    "returning false (fail-safe)"
            )
            false
        }
    }

    /**
     * Extracts the package name from a PACKAGE_ADDED / PACKAGE_REMOVED intent's
     * data URI.
     *
     * Uses `getEncodedSchemeSpecificPart()` to match the reference's behaviour exactly.
     * For most package names this is identical to `getSchemeSpecificPart()`,
     * but for package names containing URL-reserved characters (rare but
     * possible with some sideloaded apps), the encoded form is the canonical
     * representation.
     *
     * @param data the intent's data URI (`intent.data`)
     * @return the package name, or `null` if the data URI is null or empty
     */
    fun extractPackageName(data: android.net.Uri?): String? {
        if (data == null) return null
        val raw = data.encodedSchemeSpecificPart
        if (raw.isNullOrBlank()) return null
        return raw
    }
}
