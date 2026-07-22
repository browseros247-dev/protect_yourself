package protect.yourself.features.blockScreen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusItemModel
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.ui.PornBlockActivity

/**
 * Regression tests for the v1.0.68 block-screen round:
 *
 *  - TIMER-DEFAULT-01: default countdown of 3s whenever a valid custom value
 *    has not been set (the previous getter returned 0 → instant close, and no
 *    code path existed that could ever persist a custom value).
 *  - BLOCK-SCREEN-02: PornBlockActivity exposes an isShowing flag so the
 *    fallback launcher can verify the screen actually appeared (guards
 *    against silent background-activity-launch drops on API 29+).
 *
 * The getter-under-test reads the REAL Room DB (Robolectric-backed), so all
 * stored/unstored/invalid value transitions are exercised end-to-end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BlockScreenReliabilityTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var switchValues: SwitchStatusValues

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getInstance(app)
        switchValues = SwitchStatusValues(db.switchStatusDao())
        // Start each case from the canonical "not set" state.
        db.switchStatusDao().deleteByKey(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)
    }

    private fun store(rawValue: String) = runBlocking {
        db.switchStatusDao().upsert(
            SwitchStatusItemModel(
                key = SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET,
                value = rawValue,
                type = "number"
            )
        )
    }

    // ============================================================================
    // TIMER-DEFAULT-01 — default 3s whenever no valid custom value is set
    // ============================================================================

    @Test
    fun `default is 3 seconds`() {
        assertThat(SwitchStatusValues.DEFAULT_BLOCK_SCREEN_COUNTDOWN_SECONDS).isEqualTo(3)
    }

    @Test
    fun `unset value returns the 3s default`() = runBlocking {
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    @Test
    fun `zero stored returns the 3s default`() = runBlocking {
        store("0")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    @Test
    fun `negative stored returns the 3s default`() = runBlocking {
        store("-5")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    @Test
    fun `unparsable stored returns the 3s default`() = runBlocking {
        store("abc")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    @Test
    fun `above-max stored returns the 3s default`() = runBlocking {
        store("301")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    // ============================================================================
    // TIMER-DEFAULT-01 — valid custom values are honored
    // ============================================================================

    @Test
    fun `custom value 7 is honored`() = runBlocking {
        store("7")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(7)
    }

    @Test
    fun `boundary values 1 and 300 are honored`() = runBlocking {
        store("1")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(1)
        store("300")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(300)
    }

    @Test
    fun `deleting a custom value falls back to the 3s default`() = runBlocking {
        store("10")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(10)
        db.switchStatusDao().deleteByKey(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    // ============================================================================
    // BLOCK-SCREEN-02 — visibility flag contract for the fallback verifier
    // ============================================================================

    @Test
    fun `block activity visibility flag defaults to not showing`() {
        assertThat(PornBlockActivity.isShowing.get()).isFalse()
    }
}
