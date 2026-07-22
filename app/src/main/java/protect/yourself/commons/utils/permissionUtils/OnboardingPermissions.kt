package protect.yourself.commons.utils.permissionUtils

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import protect.yourself.features.protectedApps.AccessibilityPersistUtils
import timber.log.Timber

/**
 * OB-PERM (v1.0.66): Central permission state evaluation + system-intent
 * factories for the onboarding permission checklist.
 *
 * Why this exists: previously the onboarding flow (OnboardingPage) only asked
 * for terms acceptance and printed a passive "please enable accessibility"
 * hint. It never requested any of the runtime / special-access permissions
 * the app's core subsystems depend on:
 *
 *  - OB-PERM-01  POST_NOTIFICATIONS (runtime, API 33+) — without it every
 *    user-facing alert (accessibility-disabled, VPN-permission-required,
 *    daily report, overlay-permission) is silently invisible on 33+.
 *  - OB-PERM-02  Battery-optimization exemption (special access, app ops) —
 *    without it Doze / OEM task killers throttle WorkManager reconciles,
 *    exact alarms and the boot-time VPN restore path.
 *  - OB-PERM-04  SCHEDULE_EXACT_ALARM special access (API 31+) — degrades
 *    schedule alarms + the VpnRestoreHelper backup alarm to inexact.
 *  - OB-PERM-03  Accessibility service enablement (settings-only, cannot be
 *    requested programmatically) — needs an actionable row with live state
 *    instead of passive text.
 *
 * Design notes:
 *  - [evaluate]/[buildRows] return plain data; the composable layer renders
 *    and dispatches. [buildRows] is pure Kotlin (all state injected) so the
 *    full granted/denied/applicable matrix is unit-testable without Android.
 *  - Every state read is wrapped in try/catch — a broken OEM Settings
 *    provider must never crash onboarding (fails closed: permission
 *    reported as "not granted", user can still finish onboarding).
 *  - The direct battery-exemption intent requires the manifest permission
 *    REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (added in v1.0.66). The UI falls
 *    back to the generic battery-optimization settings list if the direct
 *    request intent cannot be resolved/handled (some OEMs).
 */
object OnboardingPermissions {

    private const val TAG = "OnboardingPermissions"

    /** Permission rows shown during onboarding, in display order. */
    enum class Kind { NOTIFICATIONS, BATTERY_OPTIMIZATION, EXACT_ALARMS, ACCESSIBILITY }

    /** REQUIRED = app features silently break without it; RECOMMENDED = reliability improves. */
    enum class Urgency { REQUIRED, RECOMMENDED }

    /**
     * Immutable snapshot of one permission row.
     *
     * @param applicable false when the platform version does not need this
     *  permission at all (rendered as an informational "granted" row).
     */
    data class Row(
        val kind: Kind,
        val urgency: Urgency,
        val granted: Boolean,
        val applicable: Boolean,
        val title: String,
        val description: String
    )

    // ============================================================================
    // Platform gates (pure — unit-testable)
    // ============================================================================

    /** Runtime POST_NOTIFICATIONS prompt only exists on API 33+. */
    fun notificationsRuntimePromptRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.TIRAMISU

    /** SCHEDULE_EXACT_ALARM special app access only exists on API 31+. */
    fun exactAlarmsApplicable(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
        sdkInt >= Build.VERSION_CODES.S

    // ============================================================================
    // Live state reads (fail-closed; never throw)
    // ============================================================================

    /**
     * Effective "user will see our notifications" state. On API 33+
     * NotificationManagerCompat.areNotificationsEnabled() reflects the
     * POST_NOTIFICATIONS runtime grant; on older versions it reflects the
     * user-level notification block — exactly what we need in both cases.
     */
    fun areNotificationsEnabled(context: Context): Boolean = try {
        NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (t: Throwable) {
        Timber.w(t, "$TAG: areNotificationsEnabled() failed — assuming disabled")
        false
    }

    /** True when the app is on the battery-optimization whitelist. */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean = try {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
    } catch (t: Throwable) {
        Timber.w(t, "$TAG: isIgnoringBatteryOptimizations() failed — assuming not exempt")
        false
    }

    /** True when exact alarms can be scheduled (always true below API 31). */
    fun canScheduleExactAlarms(context: Context): Boolean =
        if (!exactAlarmsApplicable()) true else try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            am?.canScheduleExactAlarms() == true
        } catch (t: Throwable) {
            Timber.w(t, "$TAG: canScheduleExactAlarms() failed — assuming denied")
            false
        }

    /**
     * True when our accessibility service is EFFECTIVELY enabled (v1.0.69,
     * A11Y-PERSIST-03): our entry is in enabled_accessibility_services AND the
     * master accessibility_enabled switch is ON. Entry-only checks report
     * "granted" while blocking is actually dead after an OEM master-switch
     * flip — the onboarding row must reflect the real blocking capability.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean = try {
        AccessibilityPersistUtils.isAccessibilityEffectivelyEnabled(context)
    } catch (t: Throwable) {
        Timber.w(t, "$TAG: accessibility state read failed — assuming disabled")
        false
    }

    // ============================================================================
    // Row construction
    // ============================================================================

    /**
     * Pure row builder — the full state matrix is injected so unit tests can
     * cover every combination (granted/denied × applicable/not-applicable)
     * without Android framework dependencies.
     */
    fun buildRows(
        sdkInt: Int,
        notificationsEnabled: Boolean,
        batteryIgnored: Boolean,
        exactAlarmsAllowed: Boolean,
        accessibilityEnabled: Boolean
    ): List<Row> {
        val notifApplicable = notificationsRuntimePromptRequired(sdkInt)
        val exactApplicable = exactAlarmsApplicable(sdkInt)
        return listOf(
            Row(
                kind = Kind.NOTIFICATIONS,
                urgency = Urgency.REQUIRED,
                applicable = notifApplicable,
                // Pre-33 there is no runtime gate to satisfy — nothing to do.
                granted = if (notifApplicable) notificationsEnabled else true,
                title = "Notifications",
                description = if (notifApplicable) {
                    "Alerts you when protection stops: accessibility service turned off, " +
                        "VPN permission needed, schedule reminders."
                } else {
                    "Not required on this Android version — notifications work by default."
                }
            ),
            Row(
                kind = Kind.BATTERY_OPTIMIZATION,
                urgency = Urgency.RECOMMENDED,
                applicable = true,
                granted = batteryIgnored,
                title = "Background running (battery)",
                description = "Exempt from battery optimization so blocking, schedules and " +
                    "VPN auto-restart keep working when the screen is off. " +
                    "Choose \"Allow\" in the next dialog."
            ),
            Row(
                kind = Kind.EXACT_ALARMS,
                urgency = Urgency.RECOMMENDED,
                applicable = exactApplicable,
                // The gate does not exist pre-31 — treat as satisfied.
                granted = if (exactApplicable) exactAlarmsAllowed else true,
                title = "Alarms & reminders",
                description = if (exactApplicable) {
                    "Exact alarms run schedules and the VPN boot-restore on time. " +
                        "Enable \"Alarms & reminders\" for Protect Yourself."
                } else {
                    "Not required on this Android version."
                }
            ),
            Row(
                kind = Kind.ACCESSIBILITY,
                urgency = Urgency.REQUIRED,
                applicable = true,
                granted = accessibilityEnabled,
                title = "Accessibility service",
                description = "Core of content & app blocking. In system settings, enable " +
                    "Settings → Accessibility → Protect Yourself → ON."
            )
        )
    }

    /**
     * Live evaluation against the device. Never throws — a failing read is
     * logged and reported as not-granted so the checklist still renders.
     */
    fun evaluate(context: Context): List<Row> = buildRows(
        sdkInt = Build.VERSION.SDK_INT,
        notificationsEnabled = areNotificationsEnabled(context),
        batteryIgnored = isIgnoringBatteryOptimizations(context),
        exactAlarmsAllowed = canScheduleExactAlarms(context),
        accessibilityEnabled = isAccessibilityServiceEnabled(context)
    )

    /** True only when every applicable REQUIRED row is granted. */
    fun allRequiredGranted(rows: List<Row>): Boolean =
        rows.none { it.applicable && it.urgency == Urgency.REQUIRED && !it.granted }

    /** Kinds of applicable rows that are still not granted (for skip logging). */
    fun missingKinds(rows: List<Row>): List<Kind> =
        rows.filter { it.applicable && !it.granted }.map { it.kind }

    // ============================================================================
    // System-intent factories (pure — unit-testable)
    // ============================================================================

    /**
     * Direct "ignore battery optimizations?" whitelist dialog.
     * Requires <uses-permission REQUEST_IGNORE_BATTERY_OPTIMIZATIONS>.
     * Caller must be ready to fall back to [batteryOptimizationSettingsIntent]
     * when this intent cannot be handled (missing handler / OEM blocks it).
     */
    fun batteryOptimizationRequestIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Generic battery-optimization list (no manifest permission required). */
    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** "Alarms & reminders" special app access screen (API 31+). */
    fun exactAlarmSettingsIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** System accessibility settings list (service must be toggled manually). */
    fun accessibilitySettingsIntent(): Intent =
        Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Per-app notification settings (used when the runtime prompt is unavailable or was denied). */
    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
