package protect.yourself.database

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
import protect.yourself.database.blockScreensCount.BlockScreenCountItemModel
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import protect.yourself.database.stopMeSessionCount.StopMeSessionCountItemModel
import protect.yourself.database.streakDates.StreakDatesItemModel
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel

/**
 * Unit tests for all 9 Room DAOs.
 *
 * Uses in-memory Room database (Robolectric for context).
 * Phase 2 covers core CRUD operations for each DAO.
 */
class AllDaosTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ===== BlockScreenCountDao =====

    @Test
    fun `blockScreenCountDao upsert and getCount`() = runBlocking {
        db.blockScreenCountDao().upsert(BlockScreenCountItemModel(0, 42))
        val item = db.blockScreenCountDao().getCount()
        assertThat(item).isNotNull()
        assertThat(item!!.count).isEqualTo(42)
    }

    @Test
    fun `blockScreenCountDao increment increments count`() = runBlocking {
        db.blockScreenCountDao().upsert(BlockScreenCountItemModel(0, 10))
        db.blockScreenCountDao().increment()
        val item = db.blockScreenCountDao().getCount()
        assertThat(item!!.count).isEqualTo(11)
    }

    @Test
    fun `blockScreenCountDao reset sets count to 0`() = runBlocking {
        db.blockScreenCountDao().upsert(BlockScreenCountItemModel(0, 100))
        db.blockScreenCountDao().reset()
        val item = db.blockScreenCountDao().getCount()
        assertThat(item!!.count).isEqualTo(0)
    }

    // ===== StopMeSessionCountDao =====

    @Test
    fun `stopMeSessionCountDao upsert and get`() = runBlocking {
        db.stopMeSessionCountDao().upsert(StopMeSessionCountItemModel(0, 5))
        val item = db.stopMeSessionCountDao().get()
        assertThat(item).isNotNull()
        assertThat(item!!.duration).isEqualTo(5)
    }

    @Test
    fun `stopMeSessionCountDao increment increments`() = runBlocking {
        db.stopMeSessionCountDao().upsert(StopMeSessionCountItemModel(0, 5))
        db.stopMeSessionCountDao().increment()
        assertThat(db.stopMeSessionCountDao().get()!!.duration).isEqualTo(6)
    }

    // ===== SelectedAppItemModel DAO =====

    @Test
    fun `selectedAppsListDao upsert and observe by identifier`() = runBlocking {
        val app = SelectedAppItemModel(
            key = "test_1",
            packageName = "com.test.app",
            appName = "Test App",
            identifier = SelectedAppListIdentifier.BLOCK_APPS.value,
            isSelected = true
        )
        db.selectedAppsListDao().upsert(app)
        val items = db.selectedAppsListDao().observeByIdentifier(SelectedAppListIdentifier.BLOCK_APPS.value).first()
        assertThat(items).hasSize(1)
        assertThat(items[0].packageName).isEqualTo("com.test.app")
    }

    @Test
    fun `selectedAppsListDao getSelectedByIdentifier returns only selected`() = runBlocking {
        db.selectedAppsListDao().upsertAll(listOf(
            SelectedAppItemModel("k1", "com.app1", "App 1", SelectedAppListIdentifier.BLOCK_APPS.value, true),
            SelectedAppItemModel("k2", "com.app2", "App 2", SelectedAppListIdentifier.BLOCK_APPS.value, false),
            SelectedAppItemModel("k3", "com.app3", "App 3", SelectedAppListIdentifier.BLOCK_APPS.value, true)
        ))
        val selected = db.selectedAppsListDao().getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_APPS.value)
        assertThat(selected).hasSize(2)
        assertThat(selected.map { it.packageName }).containsExactly("com.app1", "com.app3")
    }

    @Test
    fun `selectedAppsListDao deleteByKey removes only that item`() = runBlocking {
        db.selectedAppsListDao().upsertAll(listOf(
            SelectedAppItemModel("k1", "com.app1", "App 1", SelectedAppListIdentifier.BLOCK_APPS.value, true),
            SelectedAppItemModel("k2", "com.app2", "App 2", SelectedAppListIdentifier.BLOCK_APPS.value, true)
        ))
        db.selectedAppsListDao().deleteByKey("k1")
        val remaining = db.selectedAppsListDao().observeByIdentifier(SelectedAppListIdentifier.BLOCK_APPS.value).first()
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].packageName).isEqualTo("com.app2")
    }

    // ===== SelectedKeywordDao =====

    @Test
    fun `selectedKeywordDao upsert and countByIdentifier`() = runBlocking {
        db.selectedKeywordDao().upsertAll(listOf(
            SelectedKeywordItemModel("k1", "porn", SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value, true),
            SelectedKeywordItemModel("k2", "xxx", SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value, true),
            SelectedKeywordItemModel("k3", "nofap", SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value, true)
        ))
        assertThat(db.selectedKeywordDao().countByIdentifier(SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value)).isEqualTo(2)
        assertThat(db.selectedKeywordDao().countByIdentifier(SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value)).isEqualTo(1)
    }

    @Test
    fun `selectedKeywordDao deleteByIdentifier removes all in category`() = runBlocking {
        db.selectedKeywordDao().upsertAll(listOf(
            SelectedKeywordItemModel("k1", "porn", SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value, true),
            SelectedKeywordItemModel("k2", "xxx", SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value, true),
            SelectedKeywordItemModel("k3", "nofap", SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value, true)
        ))
        db.selectedKeywordDao().deleteByIdentifier(SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value)
        assertThat(db.selectedKeywordDao().countByIdentifier(SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value)).isEqualTo(0)
        assertThat(db.selectedKeywordDao().countByIdentifier(SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value)).isEqualTo(1)
    }

    // ===== StopMeDurationDao =====

    @Test
    fun `stopMeDurationDao separates instant and schedule durations`() = runBlocking {
        db.stopMeDurationDao().upsert(StopMeDurationItemModel("inst1", 900000L, 0L, 0, 0L, 0L))   // 15min instant
        db.stopMeDurationDao().upsert(StopMeDurationItemModel("sched1", 1800000L, 0L, 0b01111111, 32400000L, 0L))  // 30min scheduled

        val instant = db.stopMeDurationDao().observeInstantDurations().first()
        val scheduled = db.stopMeDurationDao().observeScheduleDurations().first()

        assertThat(instant).hasSize(1)
        assertThat(instant[0].key).isEqualTo("inst1")
        assertThat(scheduled).hasSize(1)
        assertThat(scheduled[0].key).isEqualTo("sched1")
    }

    @Test
    fun `stopMeDurationDao getActiveInstantSession returns null when no session active`() = runBlocking {
        val now = System.currentTimeMillis()
        val active = db.stopMeDurationDao().getActiveInstantSession(now)
        assertThat(active).isNull()
    }

    @Test
    fun `stopMeDurationDao getActiveInstantSession returns active session`() = runBlocking {
        val now = System.currentTimeMillis()
        val endTime = now + 60000L  // 1 minute from now
        db.stopMeDurationDao().upsert(StopMeDurationItemModel("active", 60000L, endTime, 0, 0L, 0L))
        val active = db.stopMeDurationDao().getActiveInstantSession(now)
        assertThat(active).isNotNull()
        assertThat(active!!.key).isEqualTo("active")
    }

    // ===== StreakDatesDao =====

    @Test
    fun `streakDatesDao upsert and observeAll`() = runBlocking {
        val now = System.currentTimeMillis()
        db.streakDatesDao().upsert(StreakDatesItemModel(now - 86400000L, now - 86400000L + 86399999L, "", ""))
        db.streakDatesDao().upsert(StreakDatesItemModel(now, now + 86399999L, "", ""))
        val all = db.streakDatesDao().observeAll().first()
        assertThat(all).hasSize(2)
    }

    @Test
    fun `streakDatesDao separates active and relapse days`() = runBlocking {
        val now = System.currentTimeMillis()
        db.streakDatesDao().upsert(StreakDatesItemModel(now - 86400000L * 2, now - 86400000L * 2 + 86399999L, "", ""))
        db.streakDatesDao().upsert(StreakDatesItemModel(now - 86400000L, now - 86400000L + 86399999L, "URGE", "gave in"))
        db.streakDatesDao().upsert(StreakDatesItemModel(now, now + 86399999L, "", ""))

        val active = db.streakDatesDao().observeActiveStreakDays().first()
        val relapse = db.streakDatesDao().observeRelapseDays().first()

        assertThat(active).hasSize(2)
        assertThat(relapse).hasSize(1)
        assertThat(relapse[0].type).isEqualTo("URGE")
    }

    @Test
    fun `streakDatesDao countActiveStreakDays returns correct count`() = runBlocking {
        val now = System.currentTimeMillis()
        db.streakDatesDao().upsert(StreakDatesItemModel(now - 86400000L * 2, 0, "", ""))
        db.streakDatesDao().upsert(StreakDatesItemModel(now - 86400000L, 0, "", ""))
        db.streakDatesDao().upsert(StreakDatesItemModel(now, 0, "URGE", ""))

        assertThat(db.streakDatesDao().countActiveStreakDays()).isEqualTo(2)
    }

    // ===== VpnCustomDnsDao =====

    @Test
    fun `vpnCustomDnsDao upsert and getSelected`() = runBlocking {
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("preset1", "Preset 1", "1.1.1.3", "1.0.0.3", false))
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("preset2", "Preset 2", "8.8.8.8", "8.8.4.4", true))

        val selected = db.vpnCustomDnsDao().getSelected()
        assertThat(selected).isNotNull()
        assertThat(selected!!.key).isEqualTo("preset2")
    }

    @Test
    fun `vpnCustomDnsDao setSelected sets only matching key as selected`() = runBlocking {
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("preset1", "Preset 1", "1.1.1.3", "1.0.0.3", true))
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("preset2", "Preset 2", "8.8.8.8", "8.8.4.4", false))

        db.vpnCustomDnsDao().setSelected("preset2")

        val selected = db.vpnCustomDnsDao().getSelected()
        assertThat(selected!!.key).isEqualTo("preset2")
    }

    @Test
    fun `vpnCustomDnsDao deleteByKey removes only that item`() = runBlocking {
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("preset1", "Preset 1", "1.1.1.3", "1.0.0.3", true))
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("preset2", "Preset 2", "8.8.8.8", "8.8.4.4", false))

        db.vpnCustomDnsDao().deleteByKey("preset1")
        val all = db.vpnCustomDnsDao().observeAll().first()
        assertThat(all).hasSize(1)
        assertThat(all[0].key).isEqualTo("preset2")
    }

    // ===== PendingRequestDao (Phase 5 will use heavily) =====

    @Test
    fun `pendingRequestDao upsert and getByKey`() = runBlocking {
        val now = System.currentTimeMillis()
        val request = protect.yourself.database.pendingRequests.PendingRequestItemModel(
            key = "req1",
            requestIdentifier = "switch_off_vpn",
            appName = "Protect Yourself",
            keyWord = "VPN",
            packageName = "protect.yourself",
            switchNumber = 13,
            itemKey = "vpn_switch",
            itemType = "switch",
            requestDisplayMessage = "Disable VPN",
            requestSubmitTime = now,
            requestOffTime = now + 86400000L,
            apType = 3,  // REAL_FRIEND
            approvalType = 0  // pending
        )
        db.pendingRequestDao().upsert(request)
        val retrieved = db.pendingRequestDao().getByKey("req1")
        assertThat(retrieved).isNotNull()
        assertThat(retrieved!!.requestIdentifier).isEqualTo("switch_off_vpn")
    }

    @Test
    fun `pendingRequestDao hasPendingRequest returns true when pending exists`() = runBlocking {
        val now = System.currentTimeMillis()
        db.pendingRequestDao().upsert(protect.yourself.database.pendingRequests.PendingRequestItemModel(
            key = "req1", requestIdentifier = "id", appName = "App", keyWord = "kw",
            packageName = "pkg", switchNumber = 1, itemKey = "k", itemType = "switch",
            requestDisplayMessage = "msg", requestSubmitTime = now, requestOffTime = now,
            apType = 3, approvalType = 0
        ))
        assertThat(db.pendingRequestDao().hasPendingRequest()).isTrue()
    }

    @Test
    fun `pendingRequestDao updateApprovalStatus changes status`() = runBlocking {
        val now = System.currentTimeMillis()
        db.pendingRequestDao().upsert(protect.yourself.database.pendingRequests.PendingRequestItemModel(
            key = "req1", requestIdentifier = "id", appName = "App", keyWord = "kw",
            packageName = "pkg", switchNumber = 1, itemKey = "k", itemType = "switch",
            requestDisplayMessage = "msg", requestSubmitTime = now, requestOffTime = now,
            apType = 3, approvalType = 0
        ))
        db.pendingRequestDao().updateApprovalStatus("req1", 1)  // approved
        val updated = db.pendingRequestDao().getByKey("req1")
        assertThat(updated!!.approvalType).isEqualTo(1)
    }
}
