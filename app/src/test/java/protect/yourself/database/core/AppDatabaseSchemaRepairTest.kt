package protect.yourself.database.core

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import protect.yourself.database.vpnCustomDns.VpnCustomDnsItemModel
import protect.yourself.features.blockerPage.utils.DefaultDnsPresets

/**
 * Tests for the v1.0.49 database schema repair fixes.
 *
 * Verifies that Room generates camelCase column names (matching entity field
 * names without @ColumnInfo) and that raw SQL using camelCase succeeds while
 * snake_case fails — documenting the fixed bug.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppDatabaseSchemaRepairTest {

    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `vpn_custom_dns columns are camelCase`() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA table_info(vpn_custom_dns)")
        val columnNames = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex >= 0) {
                    columnNames.add(c.getString(nameIndex))
                }
            }
        }
        assertThat(columnNames).containsExactly(
            "key", "displayName", "firstDns", "secondDns", "isSelected"
        )
        Unit
    }

    @Test
    fun `vpn_custom_dns DAO insert and query works with camelCase columns`() = runBlocking {
        val preset = VpnCustomDnsItemModel(
            key = "test_preset", displayName = "Test Preset",
            firstDns = "1.1.1.1", secondDns = "1.0.0.1", isSelected = true
        )
        db.vpnCustomDnsDao().upsert(preset)
        val all = db.vpnCustomDnsDao().getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].displayName).isEqualTo("Test Preset")
        assertThat(all[0].firstDns).isEqualTo("1.1.1.1")
        Unit
    }

    @Test
    fun `vpn_custom_dns setSelected query works with camelCase column`() = runBlocking {
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("k1", "P1", "1.1.1.1", "1.0.0.1", false))
        db.vpnCustomDnsDao().upsert(VpnCustomDnsItemModel("k2", "P2", "8.8.8.8", "8.8.4.4", false))
        db.vpnCustomDnsDao().setSelected("k2")
        val selected = db.vpnCustomDnsDao().getSelected()
        assertThat(selected!!.key).isEqualTo("k2")
        Unit
    }

    @Test
    fun `raw SQL INSERT with camelCase columns succeeds`() = runBlocking {
        val supportDb = db.openHelper.writableDatabase
        for (preset in DefaultDnsPresets.ALL) {
            supportDb.execSQL(
                "INSERT OR IGNORE INTO vpn_custom_dns (`key`, displayName, firstDns, secondDns, isSelected) VALUES (?, ?, ?, ?, ?)",
                arrayOf(preset.key, preset.displayName, preset.firstDns, preset.secondDns, preset.isSelectedByDefault)
            )
        }
        val all = db.vpnCustomDnsDao().getAll()
        assertThat(all).hasSize(DefaultDnsPresets.ALL.size)
        Unit
    }

    @Test(expected = Exception::class)
    fun `raw SQL INSERT with snake_case columns fails - documents the v1_0_49 bug`() {
        val supportDb = db.openHelper.writableDatabase
        supportDb.execSQL(
            "INSERT OR IGNORE INTO vpn_custom_dns (`key`, display_name, first_dns, second_dns, is_selected) VALUES (?, ?, ?, ?, ?)",
            arrayOf("test", "Test", "1.1.1.1", "1.0.0.1", false)
        )
    }

    @Test
    fun `DefaultDnsPresets contains 4 valid presets with Cloudflare selected`() {
        assertThat(DefaultDnsPresets.ALL).hasSize(4)
        for (preset in DefaultDnsPresets.ALL) {
            assertThat(preset.key).isNotEmpty()
            assertThat(preset.displayName).isNotEmpty()
            assertThat(preset.firstDns).isNotEmpty()
        }
        val selectedCount = DefaultDnsPresets.ALL.count { it.isSelectedByDefault }
        assertThat(selectedCount).isEqualTo(1)
        Unit
    }

    @Test
    fun `stop_me_duration_table columns are camelCase`() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA table_info(stop_me_duration_table)")
        val columnNames = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex >= 0) {
                    columnNames.add(c.getString(nameIndex))
                }
            }
        }
        assertThat(columnNames).containsExactly(
            "key", "duration", "endTime", "days", "startTime", "startTimeDayMillis"
        )
        Unit
    }

    @Test
    fun `selected_apps_table columns are camelCase`() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA table_info(selected_apps_table)")
        val columnNames = mutableListOf<String>()
        cursor.use { c ->
            while (c.moveToNext()) {
                val nameIndex = c.getColumnIndex("name")
                if (nameIndex >= 0) {
                    columnNames.add(c.getString(nameIndex))
                }
            }
        }
        assertThat(columnNames).containsExactly(
            "key", "packageName", "appName", "identifier", "isSelected"
        )
        Unit
    }
}
