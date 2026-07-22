package protect.yourself.features.vpnReview

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import protect.yourself.commons.utils.notificationUtils.NotificationHelper
import protect.yourself.commons.utils.vpn.VpnRestoreHelper
import protect.yourself.features.blockerPage.service.MyVpnService

/**
 * Regression tests for the comprehensive VPN review fixes (v1.0.64):
 *
 *  - VPN-STOP-02: MyVpnService.stop() must NOT start the service when it is
 *    already dead (avoids the placeholder "Connecting…" notification flash).
 *  - VPN-NOTIF-04: the "VPN permission required" notification copy is now
 *    scenario-specific (schedule default vs. boot-restore custom copy).
 *  - VPN-STATE-05 / BOOT-VPN: VpnRestoreHelper.restoreIfEnabled no-ops with
 *    NOT_ENABLED when the switch is OFF (state source of truth = DB).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VpnReviewFixesTest {

    // ===== VPN-STOP-02 =====

    @Test
    fun `stop does not start the service when the VPN is not running`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        // Fresh test JVM: instance == null and observableVpnState == IDLE.
        assertThat(MyVpnService.isRunning()).isFalse()
        assertThat(MyVpnService.observableVpnState).isEqualTo(MyVpnService.VpnState.IDLE)

        MyVpnService.stop(app)

        // With the pre-fix code this was non-null: the service was STARTED
        // just to be stopped, flashing the placeholder notification.
        assertThat(shadowOf(app).peekNextStartedService()).isNull()
    }

    // ===== VPN-NOTIF-04 =====

    @Test
    fun `vpn permission notification keeps scheduled-restriction copy by default`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.showVpnPermissionRequiredNotification(context)

        val notification = lastNotification(context)
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE))
            .contains("Scheduled app restriction")
    }

    @Test
    fun `vpn permission notification uses custom copy when provided`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.showVpnPermissionRequiredNotification(
            context = context,
            title = "VPN protection is off",
            text = "VPN permission was removed. Tap to re-enable VPN protection.",
            bigText = "Big text"
        )

        val notification = lastNotification(context)
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE))
            .isEqualTo("VPN protection is off")
        assertThat(notification.extras.getString(Notification.EXTRA_TEXT))
            .isEqualTo("VPN permission was removed. Tap to re-enable VPN protection.")
        assertThat(notification.extras.getString(Notification.EXTRA_BIG_TEXT))
            .isEqualTo("Big text")
    }

    // ===== BOOT-VPN / VPN-STATE-05 state source of truth =====

    @Test
    fun `restoreIfEnabled returns NOT_ENABLED when vpn switch is off`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Empty database → VPN_SWITCH defaults to OFF.
        val outcome = VpnRestoreHelper.restoreIfEnabled(context, trigger = "unit_test")
        assertThat(outcome).isEqualTo(VpnRestoreHelper.RestoreOutcome.NOT_ENABLED)
    }

    @Test
    fun `backup alarm delay is longer than the worker verify window`() {
        // Structural guard for the layered boot-restore design: the backup
        // alarm must fire AFTER the primary WorkManager path had a chance to
        // run, so it acts as a genuine backup rather than a duplicate start.
        assertThat(VpnRestoreHelper.BOOT_ALARM_DELAY_MS).isGreaterThan(10_000L)
        assertThat(VpnRestoreHelper.BOOT_ALARM_DELAY_MS).isLessThan(120_000L)
    }

    private fun lastNotification(context: Context): Notification {
        val nm = context.getSystemService(NotificationManager::class.java)
        val all = shadowOf(nm).allNotifications
        assertThat(all).isNotEmpty()
        val matches = all.filter { it.extras.containsKey(Notification.EXTRA_TITLE) }
        return matches.last()
    }
}
