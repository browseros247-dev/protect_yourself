package protect.yourself.commons.utils.permissionUtils

import android.app.AlarmManager
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import protect.yourself.commons.utils.notificationUtils.NotificationHelper

/**
 * Tests for the onboarding permission checklist (OB-PERM v1.0.66):
 *
 *  - Pure row matrix (buildRows): granted/denied × applicable/not-applicable
 *    across SDK boundaries (26/31/33/34).
 *  - Live state reads via Robolectric shadows (battery, exact alarms,
 *    notifications, accessibility).
 *  - Intent factories (battery direct + fallback, exact alarm, accessibility,
 *    app notification settings).
 *  - OB-PERM-05: NotificationHelper gates posting on areNotificationsEnabled.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnboardingPermissionsTest {

    private fun context(): Application = ApplicationProvider.getApplicationContext()

    // ============================================================================
    // Pure row matrix
    // ============================================================================

    @Test
    fun `sdk 26 - runtime-gated rows collapse to granted and not-applicable`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 26,
            notificationsEnabled = false,   // irrelevant pre-33
            batteryIgnored = false,
            exactAlarmsAllowed = false,     // irrelevant pre-31
            accessibilityEnabled = false
        )
        assertThat(rows).hasSize(4)
        val notif = rows.first { it.kind == OnboardingPermissions.Kind.NOTIFICATIONS }
        assertThat(notif.applicable).isFalse()
        assertThat(notif.granted).isTrue()
        val alarm = rows.first { it.kind == OnboardingPermissions.Kind.EXACT_ALARMS }
        assertThat(alarm.applicable).isFalse()
        assertThat(alarm.granted).isTrue()
        // Battery + accessibility still real on 26 → allRequiredGranted false
        // because ACCESSIBILITY (REQUIRED, applicable) is missing.
        assertThat(OnboardingPermissions.allRequiredGranted(rows)).isFalse()
    }

    @Test
    fun `sdk 34 - all denied marks both required rows missing`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 34,
            notificationsEnabled = false,
            batteryIgnored = false,
            exactAlarmsAllowed = false,
            accessibilityEnabled = false
        )
        assertThat(rows.all { it.applicable }).isTrue()
        assertThat(rows.all { !it.granted }).isTrue()
        assertThat(OnboardingPermissions.allRequiredGranted(rows)).isFalse()
        assertThat(OnboardingPermissions.missingKinds(rows)).containsExactly(
            OnboardingPermissions.Kind.NOTIFICATIONS,
            OnboardingPermissions.Kind.BATTERY_OPTIMIZATION,
            OnboardingPermissions.Kind.EXACT_ALARMS,
            OnboardingPermissions.Kind.ACCESSIBILITY
        ).inOrder()
    }

    @Test
    fun `sdk 34 - all granted yields no missing and all required satisfied`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 34,
            notificationsEnabled = true,
            batteryIgnored = true,
            exactAlarmsAllowed = true,
            accessibilityEnabled = true
        )
        assertThat(rows.all { it.granted }).isTrue()
        assertThat(OnboardingPermissions.allRequiredGranted(rows)).isTrue()
        assertThat(OnboardingPermissions.missingKinds(rows)).isEmpty()
    }

    @Test
    fun `sdk 34 - recommended rows missing do not block allRequiredGranted`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 34,
            notificationsEnabled = true,     // required ✓
            batteryIgnored = false,          // recommended ✗
            exactAlarmsAllowed = false,      // recommended ✗
            accessibilityEnabled = true      // required ✓
        )
        assertThat(OnboardingPermissions.allRequiredGranted(rows)).isTrue()
        assertThat(OnboardingPermissions.missingKinds(rows)).containsExactly(
            OnboardingPermissions.Kind.BATTERY_OPTIMIZATION,
            OnboardingPermissions.Kind.EXACT_ALARMS
        ).inOrder()
    }

    // ============================================================================
    // Platform gates
    // ============================================================================

    @Test
    fun `platform gates respect sdk boundaries`() {
        assertThat(OnboardingPermissions.notificationsRuntimePromptRequired(32)).isFalse()
        assertThat(OnboardingPermissions.notificationsRuntimePromptRequired(33)).isTrue()
        assertThat(OnboardingPermissions.notificationsRuntimePromptRequired(34)).isTrue()
        assertThat(OnboardingPermissions.exactAlarmsApplicable(30)).isFalse()
        assertThat(OnboardingPermissions.exactAlarmsApplicable(31)).isTrue()
    }

    // ============================================================================
    // Live state reads (Robolectric shadows)
    // ============================================================================

    @Test
    fun `battery exemption state follows PowerManager shadow`() {
        val app = context()
        val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, false)
        assertThat(OnboardingPermissions.isIgnoringBatteryOptimizations(app)).isFalse()
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
        assertThat(OnboardingPermissions.isIgnoringBatteryOptimizations(app)).isTrue()
        // evaluate() picks it up too
        val rows = OnboardingPermissions.evaluate(app)
        assertThat(rows.first { it.kind == OnboardingPermissions.Kind.BATTERY_OPTIMIZATION }.granted).isTrue()
    }

    @Test
    fun `exact alarm state follows AlarmManager shadow on sdk 34`() {
        val app = context()
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertThat(am).isNotNull()
        // Static setter on the shadow (Kotlin cannot call it via the instance).
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        assertThat(OnboardingPermissions.canScheduleExactAlarms(app)).isFalse()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        assertThat(OnboardingPermissions.canScheduleExactAlarms(app)).isTrue()
    }

    @Test
    fun `notifications default enabled in shadow and toggle off is seen`() {
        val app = context()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationsEnabled(true)
        assertThat(OnboardingPermissions.areNotificationsEnabled(app)).isTrue()
        shadowOf(nm).setNotificationsEnabled(false)
        assertThat(OnboardingPermissions.areNotificationsEnabled(app)).isFalse()
    }

    @Test
    fun `accessibility defaults to disabled in shadow`() {
        assertThat(OnboardingPermissions.isAccessibilityServiceEnabled(context())).isFalse()
    }

    // ============================================================================
    // OB-PERM-05: NotificationHelper posting gate
    // ============================================================================

    @Test
    fun `OB-PERM-05 - helper posts when notifications enabled`() {
        val app = context()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationsEnabled(true)
        NotificationHelper.showDailyReportNotification(app, 3)
        assertThat(shadowOf(nm).allNotifications).isNotEmpty()
    }

    @Test
    fun `OB-PERM-05 - helper skips posting when notifications disabled`() {
        val app = context()
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationsEnabled(false)
        NotificationHelper.showDailyReportNotification(app, 3)
        assertThat(shadowOf(nm).allNotifications).isEmpty()
    }

    // ============================================================================
    // Intent factories
    // ============================================================================

    @Test
    fun `battery intents - direct request carries package uri, fallback is settings list`() {
        val app = context()
        val direct = OnboardingPermissions.batteryOptimizationRequestIntent(app)
        assertThat(direct.action).isEqualTo(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        assertThat(direct.dataString).isEqualTo("package:protect.yourself")
        assertThat(direct.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)

        val fallback = OnboardingPermissions.batteryOptimizationSettingsIntent()
        assertThat(fallback.action).isEqualTo(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        assertThat(fallback.data).isNull()
    }

    @Test
    fun `exact alarm intent targets our package`() {
        val app = context()
        val intent = OnboardingPermissions.exactAlarmSettingsIntent(app)
        assertThat(intent.action).isEqualTo(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        assertThat(intent.dataString).isEqualTo("package:protect.yourself")
    }

    @Test
    fun `accessibility and notification settings intents are well formed`() {
        val app = context()
        assertThat(OnboardingPermissions.accessibilitySettingsIntent().action)
            .isEqualTo(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        val notif = OnboardingPermissions.appNotificationSettingsIntent(app)
        assertThat(notif.action).isEqualTo(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(notif.getStringExtra(Settings.EXTRA_APP_PACKAGE)).isEqualTo("protect.yourself")
    }

    // ============================================================================
    // OEM-BG (v1.0.74): BACKGROUND_AUTOSTART row
    // ============================================================================

    @Test
    fun `autostart row present on managed device - recommended and ungranted until ack`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 34,
            notificationsEnabled = true,
            batteryIgnored = true,
            exactAlarmsAllowed = true,
            accessibilityEnabled = true,
            autostartApplicable = true,
            autostartAcknowledged = false
        )
        assertThat(rows).hasSize(5)
        val auto = rows.last()
        assertThat(auto.kind).isEqualTo(OnboardingPermissions.Kind.BACKGROUND_AUTOSTART)
        assertThat(auto.urgency).isEqualTo(OnboardingPermissions.Urgency.RECOMMENDED)
        assertThat(auto.applicable).isTrue()
        assertThat(auto.granted).isFalse()
        // Missing a RECOMMENDED row must NOT block the required gate.
        assertThat(OnboardingPermissions.allRequiredGranted(rows)).isTrue()
        assertThat(OnboardingPermissions.missingKinds(rows))
            .containsExactly(OnboardingPermissions.Kind.BACKGROUND_AUTOSTART)
    }

    @Test
    fun `autostart row renders granted after acknowledgement`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 34,
            notificationsEnabled = true,
            batteryIgnored = true,
            exactAlarmsAllowed = true,
            accessibilityEnabled = true,
            autostartApplicable = true,
            autostartAcknowledged = true
        )
        val auto = rows.first { it.kind == OnboardingPermissions.Kind.BACKGROUND_AUTOSTART }
        assertThat(auto.granted).isTrue()
        assertThat(OnboardingPermissions.missingKinds(rows)).isEmpty()
    }

    @Test
    fun `autostart row omitted on unmanaged devices`() {
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 34,
            notificationsEnabled = true,
            batteryIgnored = true,
            exactAlarmsAllowed = true,
            accessibilityEnabled = true,
            autostartApplicable = false,
            autostartAcknowledged = true // ack must not resurrect the row
        )
        assertThat(rows).hasSize(4)
        assertThat(rows.none { it.kind == OnboardingPermissions.Kind.BACKGROUND_AUTOSTART }).isTrue()
    }

    @Test
    fun `autostart defaults keep legacy four row calls compiling and behaving`() {
        // Call without the new trailing params — exactly the pre-v1.0.74
        // signature usage. Behavior must be identical: 4 rows, no autostart.
        val rows = OnboardingPermissions.buildRows(
            sdkInt = 30,
            notificationsEnabled = false,
            batteryIgnored = false,
            exactAlarmsAllowed = false,
            accessibilityEnabled = false
        )
        assertThat(rows).hasSize(4)
        assertThat(rows.none { it.kind == OnboardingPermissions.Kind.BACKGROUND_AUTOSTART }).isTrue()
    }
}
