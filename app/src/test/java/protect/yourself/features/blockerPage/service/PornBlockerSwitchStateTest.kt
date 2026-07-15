package protect.yourself.features.blockerPage.service

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues

/**
 * Unit tests for the Porn Blocker switch state transitions (PB-01/PB-02/PB-04).
 *
 * These tests verify the persistence layer that backs the Porn Blocker toggle
 * in the UI — the same layer that `MyAccessibilityService.loadAllConfig` reads
 * on every `refreshBlockingConfig()` call. The tests cover all four state
 * transitions required by the task:
 *
 *   - default (ON, no DB row)
 *   - enabled (ON, DB row = "true")
 *   - disabled (OFF, DB row = "false")
 *   - re-enabled after disable (OFF → ON)
 *
 * They also verify that the `asBoolean()` parser correctly handles the
 * exact value strings produced by `storeSwitchStatus(key, value: Boolean)`
 * — which is critical because `toBooleanStrictOrNull()` is case-sensitive
 * and rejects "True", "TRUE", "1", etc.
 *
 * Uses Robolectric so `ApplicationProvider.getApplicationContext` returns a
 * real Context (required by `Room.inMemoryDatabaseBuilder`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PornBlockerSwitchStateTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AppDatabase
    private lateinit var values: SwitchStatusValues

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        values = SwitchStatusValues(database.switchStatusDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ===== Default state (no DB row) =====

    @Test
    fun `porn blocker defaults to ON when no DB row exists`() = runBlocking {
        // This matches the reference behaviour: the app ships
        // with the Porn Blocker enabled so the user is protected on first
        // launch without needing to find and toggle the switch.
        assertThat(values.isPornBlockerSwitchOn()).isTrue()
    }

    // ===== ON state (DB row = "true") =====

    @Test
    fun `porn blocker is ON when DB row is true`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()
    }

    @Test
    fun `storeSwitchStatus true persists lowercase 'true'`() = runBlocking {
        // PB-01 regression guard: asBoolean() uses toBooleanStrictOrNull()
        // which only accepts lowercase "true"/"false". If storeSwitchStatus
        // ever produces "True" or "1", the parser returns false and the
        // blocker silently turns off.
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        val row = database.switchStatusDao().get(SwitchIdentifier.PORN_BLOCKER_SWITCH)
        assertThat(row).isNotNull()
        assertThat(row!!.value).isEqualTo("true")
        assertThat(row.type).isEqualTo("boolean")
    }

    // ===== OFF state (DB row = "false") =====

    @Test
    fun `porn blocker is OFF when DB row is false`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.isPornBlockerSwitchOn()).isFalse()
    }

    @Test
    fun `storeSwitchStatus false persists lowercase 'false'`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        val row = database.switchStatusDao().get(SwitchIdentifier.PORN_BLOCKER_SWITCH)
        assertThat(row).isNotNull()
        assertThat(row!!.value).isEqualTo("false")
    }

    // ===== State transitions =====

    @Test
    fun `porn blocker OFF to ON transition persists correctly`() = runBlocking {
        // Start: OFF
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.isPornBlockerSwitchOn()).isFalse()

        // Toggle: ON
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()

        // Verify DB row was replaced (not duplicated)
        val rows = database.switchStatusDao().getAll()
        val pornRows = rows.filter { it.key == SwitchIdentifier.PORN_BLOCKER_SWITCH }
        assertThat(pornRows).hasSize(1)
        assertThat(pornRows[0].value).isEqualTo("true")
    }

    @Test
    fun `porn blocker ON to OFF transition persists correctly`() = runBlocking {
        // Start: ON
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()

        // Toggle: OFF
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.isPornBlockerSwitchOn()).isFalse()
    }

    @Test
    fun `porn blocker survives rapid ON-OFF-ON toggling`() = runBlocking {
        // Simulates a user rapidly tapping the switch. Each toggle must
        // replace the previous value — no stale rows should accumulate.
        repeat(5) {
            values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, it % 2 == 0)
        }
        // Last iteration: it=4, 4%2==0, true
        assertThat(values.isPornBlockerSwitchOn()).isTrue()

        val pornRows = database.switchStatusDao().getAll()
            .filter { it.key == SwitchIdentifier.PORN_BLOCKER_SWITCH }
        assertThat(pornRows).hasSize(1)
    }

    // ===== Flow observation (for Compose UI) =====

    @Test
    fun `observePornBlockerSwitch emits default ON`() = runBlocking {
        val value = values.observePornBlockerSwitch().first()
        assertThat(value).isTrue()
    }

    @Test
    fun `observePornBlockerSwitch emits ON after store true`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        val value = values.observePornBlockerSwitch().first()
        assertThat(value).isTrue()
    }

    @Test
    fun `observePornBlockerSwitch emits OFF after store false`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        val value = values.observePornBlockerSwitch().first()
        assertThat(value).isFalse()
    }

    @Test
    fun `observePornBlockerSwitch reflects latest value after toggle`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.observePornBlockerSwitch().first()).isTrue()

        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.observePornBlockerSwitch().first()).isFalse()

        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.observePornBlockerSwitch().first()).isTrue()
    }

    // ===== Parser robustness (PB-01 regression guard) =====

    @Test
    fun `asBoolean returns false for malformed value strings`() = runBlocking {
        // These are the values that would SILENTLY turn the Porn Blocker off
        // if they were ever written to the DB. The test documents that
        // asBoolean() is strict — only "true" (lowercase) returns true.
        val malformedValues = listOf("True", "TRUE", "1", "yes", "on", "t", "T")
        for (v in malformedValues) {
            database.switchStatusDao().upsert(
                protect.yourself.database.switchStatus.SwitchStatusItemModel(
                    key = SwitchIdentifier.PORN_BLOCKER_SWITCH,
                    value = v,
                    type = "boolean"
                )
            )
            // asBoolean('$v') should be false (strict parser)
            assertThat(values.isPornBlockerSwitchOn()).isFalse()
        }
    }

    @Test
    fun `asBoolean returns true only for lowercase 'true'`() = runBlocking {
        database.switchStatusDao().upsert(
            protect.yourself.database.switchStatus.SwitchStatusItemModel(
                key = SwitchIdentifier.PORN_BLOCKER_SWITCH,
                value = "true",
                type = "boolean"
            )
        )
        assertThat(values.isPornBlockerSwitchOn()).isTrue()
    }

    @Test
    fun `storeSwitchStatus Boolean produces parser-safe value`() = runBlocking {
        // This is the critical round-trip test: storeSwitchStatus(Boolean)
        // must produce a value that asBoolean() can parse back. If either
        // side changes without the other, the Porn Blocker toggle breaks.
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()

        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.isPornBlockerSwitchOn()).isFalse()

        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, true)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()
    }

    // ===== Null-DB-row fallback (PB-01 regression guard) =====

    @Test
    fun `isPornBlockerSwitchOn returns true when DB row is deleted`() = runBlocking {
        // Simulates a corrupted DB state where the porn_blocker_switch row
        // was deleted (e.g. by a buggy migration). The fallback must be ON
        // so the user remains protected — never silently OFF.
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.isPornBlockerSwitchOn()).isFalse()

        database.switchStatusDao().deleteByKey(SwitchIdentifier.PORN_BLOCKER_SWITCH)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()  // falls back to default
    }
}
