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

/**
 * SET-COUNTDOWN-01 (v1.0.71): the pre-v1.0.71 DB seeded
 * `block_screen_count_down_time_set = "0"`, and every read of
 * [SwitchStatusValues.getBlockScreenCountDownSeconds] logged a WARN that
 * CrashLoggingTree persisted as a crash entry — 22+ persisted "crashes" in
 * ONE field session, drowning real signal.
 *
 * The getter must now:
 *  - return the default (3s) for any invalid stored value (unchanged),
 *  - **heal the row once** so subsequent reads hit the valid path (the WARN
 *    branch can fire at most once per corrupted value — zero times after the
 *    heal),
 *  - NOT touch the DB when the row is simply absent (fresh state / user has
 *    never customised — unset already means "default").
 *
 * Runs against the REAL Room DB (Robolectric-backed), same harness as
 * [BlockScreenReliabilityTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CountdownSelfHealTest {

    private lateinit var app: Application
    private lateinit var db: AppDatabase
    private lateinit var switchValues: SwitchStatusValues

    @Before
    fun setUp() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        db = AppDatabase.getInstance(app)
        switchValues = SwitchStatusValues(db.switchStatusDao())
        db.switchStatusDao().deleteByKey(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)
    }

    private fun store(rawValue: String) = runBlocking {
        db.switchStatusDao().upsert(
            SwitchStatusItemModel(
                key = SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET,
                value = rawValue,
                type = "int"
            )
        )
    }

    private fun stored(): String? = runBlocking {
        db.switchStatusDao().get(SwitchIdentifier.BLOCK_SCREEN_COUNT_DOWN_TIME_SET)?.value
    }

    // ============================================================================
    // Legacy corruption: seeded "0" (the exact field case)
    // ============================================================================

    @Test
    fun `stored zero - returns default AND heals the row`() = runBlocking {
        store("0")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
        // Row healed → second read hits the valid path (no more WARN branch)
        assertThat(stored()).isEqualTo("3")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
    }

    @Test
    fun `negative value - returns default AND heals`() = runBlocking {
        store("-7")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
        assertThat(stored()).isEqualTo("3")
    }

    @Test
    fun `above-max value - returns default AND heals`() = runBlocking {
        store("301") // MAX is 300 — fail-safe against longer-than-max lock
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
        assertThat(stored()).isEqualTo("3")
    }

    @Test
    fun `unparsable value - returns default AND heals`() = runBlocking {
        store("abc")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
        assertThat(stored()).isEqualTo("3")
    }

    // ============================================================================
    // Valid values: untouched, no heal write
    // ============================================================================

    @Test
    fun `valid custom value - honored, row left untouched`() = runBlocking {
        store("7")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(7)
        assertThat(stored()).isEqualTo("7")
    }

    @Test
    fun `boundary values 1 and 300 - honored`() = runBlocking {
        store("1")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(1)
        store("300")
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(300)
    }

    // ============================================================================
    // Absent row (fresh state): default, NO heal write
    // ============================================================================

    @Test
    fun `absent row - returns default, row stays absent`() = runBlocking {
        assertThat(switchValues.getBlockScreenCountDownSeconds()).isEqualTo(3)
        assertThat(stored()).isNull()
    }
}
