package protect.yourself.commons.utils.permissionUtils

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.provider.Settings

/**
 * Tests for OEM-BG (v1.0.74) autostart-manager helpers:
 *
 *  - Manufacturer detection matrix (managed set, aliases, case/whitespace,
 *    unmanaged devices, Samsung explicitly excluded).
 *  - Deep-link candidate chains per OEM: expected OEM screens in order, and
 *    the universal app-details fallback ALWAYS last (even for unmanaged
 *    manufacturers — the chain is never empty).
 *  - Acknowledgement prefs round-trip via Robolectric SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OemBackgroundUtilsTest {

    private fun context(): Application = ApplicationProvider.getApplicationContext()

    // ============================================================================
    // isAutostartManagedDevice matrix
    // ============================================================================

    @Test
    fun `managed manufacturers are detected`() {
        for (m in listOf("vivo", "iqoo", "xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "huawei", "honor")) {
            assertThat(OemBackgroundUtils.isAutostartManagedDevice(m)).isTrue()
        }
    }

    @Test
    fun `detection is case and whitespace tolerant`() {
        assertThat(OemBackgroundUtils.isAutostartManagedDevice("VIVO")).isTrue()
        assertThat(OemBackgroundUtils.isAutostartManagedDevice(" vivo ")).isTrue()
        assertThat(OemBackgroundUtils.isAutostartManagedDevice("Xiaomi")).isTrue()
        assertThat(OemBackgroundUtils.isAutostartManagedDevice("BBK-vivo")).isFalse() // not an exact alias
    }

    @Test
    fun `samsung and unmanaged manufacturers are excluded`() {
        // Samsung: standard battery-optimization model — covered by the
        // existing OB-PERM-02 row; OEM autostart row must NOT appear.
        for (m in listOf("samsung", "google", "motorola", "nokia", "sony", "lenovo", "", "unknown")) {
            assertThat(OemBackgroundUtils.isAutostartManagedDevice(m)).isFalse()
        }
    }

    // ============================================================================
    // autostartCandidates chains
    // ============================================================================

    @Test
    fun `vivo chain leads with BgStartUpManager and iqoo secure fallback`() {
        val chain = OemBackgroundUtils.autostartCandidates(context(), "vivo")
        assertThat(chain).hasSize(3)
        assertThat(chain[0].intent.component?.packageName).isEqualTo("com.vivo.permissionmanager")
        assertThat(chain[0].intent.component?.className)
            .isEqualTo("com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
        assertThat(chain[1].intent.component?.packageName).isEqualTo("com.iqoo.secure")
        assertThat(chain[1].intent.component?.className).isEqualTo("com.iqoo.secure.MainActivity")
    }

    @Test
    fun `iqoo uses the vivo chain`() {
        val chain = OemBackgroundUtils.autostartCandidates(context(), "iqoo")
        assertThat(chain[0].intent.component?.packageName).isEqualTo("com.vivo.permissionmanager")
    }

    @Test
    fun `xiaomi redmi poco lead with MIUI autostart management`() {
        for (m in listOf("xiaomi", "redmi", "poco")) {
            val chain = OemBackgroundUtils.autostartCandidates(context(), m)
            assertThat(chain).hasSize(2)
            assertThat(chain[0].intent.component?.packageName).isEqualTo("com.miui.securitycenter")
            assertThat(chain[0].intent.component?.className)
                .isEqualTo("com.miui.permcenter.autostart.AutoStartManagementActivity")
        }
    }

    @Test
    fun `coloros chain covers coloros legacy and oplus variants`() {
        for (m in listOf("oppo", "realme", "oneplus")) {
            val chain = OemBackgroundUtils.autostartCandidates(context(), m)
            assertThat(chain).hasSize(4)
            assertThat(chain[0].intent.component?.className)
                .isEqualTo("com.coloros.safecenter.permission.startup.StartupAppListActivity")
            assertThat(chain[1].intent.component?.className)
                .isEqualTo("com.coloros.safecenter.startupapp.StartupAppListActivity")
            assertThat(chain[2].intent.component?.packageName).isEqualTo("com.oplus.safecenter")
        }
    }

    @Test
    fun `huawei honor chain covers startup manager and protected apps`() {
        for (m in listOf("huawei", "honor")) {
            val chain = OemBackgroundUtils.autostartCandidates(context(), m)
            assertThat(chain).hasSize(3)
            assertThat(chain[0].intent.component?.className)
                .isEqualTo("com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            assertThat(chain[1].intent.component?.className)
                .isEqualTo("com.huawei.systemmanager.optimize.process.ProtectActivity")
        }
    }

    @Test
    fun `app-details fallback is always last and targeted at our package`() {
        for (m in listOf("vivo", "xiaomi", "oppo", "huawei", "samsung", "google", "unknown")) {
            val chain = OemBackgroundUtils.autostartCandidates(context(), m)
            assertThat(chain).isNotEmpty()
            val last = chain.last()
            assertThat(last.intent.component).isNull()
            assertThat(last.intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            assertThat(last.intent.dataString).isEqualTo("package:${context().packageName}")
        }
    }

    @Test
    fun `unmanaged manufacturers get app-details only`() {
        val chain = OemBackgroundUtils.autostartCandidates(context(), "samsung")
        assertThat(chain).hasSize(1)
        assertThat(chain[0].intent.action).isEqualTo(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    @Test
    fun `openAutostartSettings launches app-details fallback without throwing`() {
        // Robolectric accepts the launch (records the started activity), so
        // the FIRST candidate might already "succeed" — what matters is that
        // no exception escapes and a boolean is returned for any OEM.
        assertThat(OemBackgroundUtils.openAutostartSettings(context())).isTrue()
    }

    // ============================================================================
    // Acknowledgement prefs
    // ============================================================================

    @Test
    fun `ack defaults to false and round trip persists in shared prefs`() {
        val ctx = context()
        ctx.getSharedPreferences("oem_background_prefs", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertThat(OemBackgroundUtils.isAutostartHintAcknowledged(ctx)).isFalse()
        assertThat(OemBackgroundUtils.markAutostartHintAcknowledged(ctx)).isTrue()
        assertThat(OemBackgroundUtils.isAutostartHintAcknowledged(ctx)).isTrue()
    }
}
