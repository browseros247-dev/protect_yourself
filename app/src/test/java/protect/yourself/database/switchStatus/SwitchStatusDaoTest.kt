package protect.yourself.database.switchStatus

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
import protect.yourself.features.blockerPage.identifiers.AccountabilityPartnerTypeIdentifiers

/**
 * Unit tests for SwitchStatusDao + SwitchStatusValues.
 *
 * Uses in-memory Room database (Robolectric for context).
 *
 * Runs under Robolectric so [ApplicationProvider.getApplicationContext] returns
 * a real Context (required by Room.inMemoryDatabaseBuilder). Without
 * Robolectric, ApplicationProvider throws "No instrumentation registered".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SwitchStatusDaoTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var database: AppDatabase
    private lateinit var dao: SwitchStatusDao
    private lateinit var values: SwitchStatusValues

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = database.switchStatusDao()
        values = SwitchStatusValues(dao)
    }

    @After
    fun tearDown() {
        database.close()
    }

    // ===== DAO basic CRUD =====

    @Test
    fun `upsert inserts new row`() = runBlocking {
        dao.upsert(SwitchStatusItemModel("test_key", "true", "boolean"))
        val item = dao.get("test_key")
        assertThat(item).isNotNull()
        assertThat(item!!.value).isEqualTo("true")
        assertThat(item.type).isEqualTo("boolean")
    }

    @Test
    fun `upsert replaces existing row`() = runBlocking {
        dao.upsert(SwitchStatusItemModel("test_key", "false", "boolean"))
        dao.upsert(SwitchStatusItemModel("test_key", "true", "boolean"))
        val item = dao.get("test_key")
        assertThat(item!!.value).isEqualTo("true")
    }

    @Test
    fun `deleteByKey removes row`() = runBlocking {
        dao.upsert(SwitchStatusItemModel("test_key", "true", "boolean"))
        dao.deleteByKey("test_key")
        assertThat(dao.get("test_key")).isNull()
    }

    @Test
    fun `observe returns flow that updates`() = runBlocking {
        dao.upsert(SwitchStatusItemModel("test_key", "false", "boolean"))
        val first = dao.observe("test_key").first()
        assertThat(first!!.value).isEqualTo("false")

        dao.upsert(SwitchStatusItemModel("test_key", "true", "boolean"))
        val second = dao.observe("test_key").first()
        assertThat(second!!.value).isEqualTo("true")
    }

    // ===== SwitchStatusValues getters =====

    @Test
    fun `isPornBlockerSwitchOn returns true by default`() = runBlocking {
        // No row inserted — default is true (matches original)
        assertThat(values.isPornBlockerSwitchOn()).isTrue()
    }

    @Test
    fun `isPornBlockerSwitchOn returns stored value when set`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        assertThat(values.isPornBlockerSwitchOn()).isFalse()
    }

    @Test
    fun `isVpnSwitchOn returns false by default`() = runBlocking {
        assertThat(values.isVpnSwitchOn()).isFalse()
    }

    @Test
    fun `isVpnSwitchOn returns stored value`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.VPN_SWITCH, true)
        assertThat(values.isVpnSwitchOn()).isTrue()
    }

    @Test
    fun `getAccountabilityPartnerType returns NONE by default`() = runBlocking {
        assertThat(values.getAccountabilityPartnerType()).isEqualTo(AccountabilityPartnerTypeIdentifiers.NONE)
    }

    @Test
    fun `getAccountabilityPartnerType returns stored value`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.ACCOUNTABILITY_PARTNER_TYPE, 1L)
        assertThat(values.getAccountabilityPartnerType()).isEqualTo(AccountabilityPartnerTypeIdentifiers.LONG_SENTENCE)
    }

    @Test
    fun `getLongSentenceCustomMessage returns default message`() = runBlocking {
        assertThat(values.getLongSentenceCustomMessage()).isEqualTo("I will not give in to my urges")
    }

    @Test
    fun `getLongSentenceCustomMessage returns stored message`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.LONG_SENTENCE_CUSTOM_MESSAGE, "Custom message")
        assertThat(values.getLongSentenceCustomMessage()).isEqualTo("Custom message")
    }

    @Test
    fun `getTimeDelayCustomDurationSeconds returns default 30`() = runBlocking {
        assertThat(values.getTimeDelayCustomDurationSeconds()).isEqualTo(30)
    }

    @Test
    fun `getTimeDelayCustomDurationSeconds returns stored value`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.TIME_DELAY_CUSTOM_DURATION, 60)
        assertThat(values.getTimeDelayCustomDurationSeconds()).isEqualTo(60)
    }

    @Test
    fun `isPremiumActive always returns false`() = runBlocking {
        // Premium is removed in rebuild — this method always returns false.
        assertThat(values.isPremiumActive()).isFalse()
    }

    @Test
    fun `isEligibleForBannerAd always returns 0`() = runBlocking {
        // Ads removed — no banner ad eligibility.
        assertThat(values.isEligibleForBannerAd()).isEqualTo(0L)
    }

    @Test
    fun `observePornBlockerSwitch emits default true`() = runBlocking {
        val value = values.observePornBlockerSwitch().first()
        assertThat(value).isTrue()
    }

    @Test
    fun `observePornBlockerSwitch emits stored false`() = runBlocking {
        values.storeSwitchStatus(SwitchIdentifier.PORN_BLOCKER_SWITCH, false)
        val value = values.observePornBlockerSwitch().first()
        assertThat(value).isFalse()
    }
}
