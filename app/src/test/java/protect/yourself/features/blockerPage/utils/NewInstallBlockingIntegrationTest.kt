package protect.yourself.features.blockerPage.utils

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
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedApps.SelectedAppItemModel
import protect.yourself.database.selectedApps.SelectedAppListIdentifier
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusItemModel
import protect.yourself.database.switchStatus.SwitchStatusValues

/**
 * Integration tests for the "Block new install apps" feature.
 *
 * Verifies the end-to-end DB operations that the
 * [protect.yourself.commons.utils.broadcastReceivers.AppSystemActionReceiverAllTimeWithData]
 * receiver performs when a new app is installed:
 *  - Switch state read (isBlockNewInstallAppsSwitchOn)
 *  - Insert into BLOCK_NEW_INSTALL_APPS list
 *  - Verify insert via getSelectedByIdentifier
 *  - Cleanup via deleteByIdentifierAndPackage
 *
 * These tests don't exercise the broadcast receiver itself (that requires
 * instrumentation tests with a real PACKAGE_ADDED broadcast). They verify
 * the DB layer that the receiver depends on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NewInstallBlockingIntegrationTest {

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

    // ===== Switch state =====

    @Test
    fun `isBlockNewInstallAppsSwitchOn returns false when switch is missing`() = runBlocking {
        val switchValues = SwitchStatusValues(db.switchStatusDao())
        assertThat(switchValues.isBlockNewInstallAppsSwitchOn()).isFalse()
    }

    @Test
    fun `isBlockNewInstallAppsSwitchOn returns false when switch is false`() = runBlocking {
        db.switchStatusDao().upsert(
            SwitchStatusItemModel(
                key = SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH,
                value = "false",
                type = "boolean"
            )
        )
        val switchValues = SwitchStatusValues(db.switchStatusDao())
        assertThat(switchValues.isBlockNewInstallAppsSwitchOn()).isFalse()
    }

    @Test
    fun `isBlockNewInstallAppsSwitchOn returns true when switch is true`() = runBlocking {
        db.switchStatusDao().upsert(
            SwitchStatusItemModel(
                key = SwitchIdentifier.BLOCK_NEW_INSTALL_APPS_SWITCH,
                value = "true",
                type = "boolean"
            )
        )
        val switchValues = SwitchStatusValues(db.switchStatusDao())
        assertThat(switchValues.isBlockNewInstallAppsSwitchOn()).isTrue()
    }

    // ===== Insert + verify (the receiver's core flow) =====

    @Test
    fun `insert into BLOCK_NEW_INSTALL_APPS and verify read back`() = runBlocking {
        val packageName = "com.example.newapp"
        val item = SelectedAppItemModel(
            key = "block_new_install_$packageName",
            packageName = packageName,
            appName = "New App",
            identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
            isSelected = true
        )
        db.selectedAppsListDao().upsert(item)

        // Verify the row is found when filtering by isSelected = 1
        val rows = db.selectedAppsListDao()
            .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].packageName).isEqualTo(packageName)
        assertThat(rows[0].isSelected).isTrue()
    }

    @Test
    fun `insert with isSelected false is NOT returned by getSelectedByIdentifier`() = runBlocking {
        val packageName = "com.example.deselected"
        val item = SelectedAppItemModel(
            key = "block_new_install_$packageName",
            packageName = packageName,
            appName = "Deselected App",
            identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
            isSelected = false  // NOT selected
        )
        db.selectedAppsListDao().upsert(item)

        val rows = db.selectedAppsListDao()
            .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
        assertThat(rows).isEmpty()
    }

    // ===== Cleanup (pre-insert cleanup matches NopoX appInstallRemoveCallback) =====

    @Test
    fun `deleteByIdentifierAndPackage removes only the target package`() = runBlocking {
        // Insert two apps
        db.selectedAppsListDao().upsertAll(listOf(
            SelectedAppItemModel(
                key = "block_new_install_com.app1",
                packageName = "com.app1",
                appName = "App 1",
                identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                isSelected = true
            ),
            SelectedAppItemModel(
                key = "block_new_install_com.app2",
                packageName = "com.app2",
                appName = "App 2",
                identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                isSelected = true
            )
        ))

        // Delete one
        db.selectedAppsListDao().deleteByIdentifierAndPackage(
            SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
            "com.app1"
        )

        val remaining = db.selectedAppsListDao()
            .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
        assertThat(remaining).hasSize(1)
        assertThat(remaining[0].packageName).isEqualTo("com.app2")
    }

    @Test
    fun `deleteByIdentifierAndPackage does not affect other identifiers`() = runBlocking {
        // Insert the same package under TWO identifiers
        db.selectedAppsListDao().upsertAll(listOf(
            SelectedAppItemModel(
                key = "block_new_install_com.app1",
                packageName = "com.app1",
                appName = "App 1",
                identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                isSelected = true
            ),
            SelectedAppItemModel(
                key = "block_apps_com.app1",
                packageName = "com.app1",
                appName = "App 1",
                identifier = SelectedAppListIdentifier.BLOCK_APPS.value,
                isSelected = true
            )
        ))

        // Delete from BLOCK_NEW_INSTALL_APPS only
        db.selectedAppsListDao().deleteByIdentifierAndPackage(
            SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
            "com.app1"
        )

        // BLOCK_NEW_INSTALL_APPS should be empty
        val newInstallRemaining = db.selectedAppsListDao()
            .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
        assertThat(newInstallRemaining).isEmpty()

        // BLOCK_APPS should still have the app
        val blockAppsRemaining = db.selectedAppsListDao()
            .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_APPS.value)
        assertThat(blockAppsRemaining).hasSize(1)
        assertThat(blockAppsRemaining[0].packageName).isEqualTo("com.app1")
    }

    // ===== PACKAGE_REMOVED cleanup (cleans ALL identifiers) =====

    @Test
    fun `PACKAGE_REMOVED cleanup removes package from all identifiers`() = runBlocking {
        // Insert the same package under ALL identifiers
        val pkg = "com.removed.app"
        val allIdentifiers = SelectedAppListIdentifier.values()
        allIdentifiers.forEach { identifier ->
            db.selectedAppsListDao().upsert(
                SelectedAppItemModel(
                    key = "${identifier.value}_$pkg",
                    packageName = pkg,
                    appName = "Removed App",
                    identifier = identifier.value,
                    isSelected = true
                )
            )
        }

        // Simulate PACKAGE_REMOVED: delete from all identifiers
        allIdentifiers.forEach { identifier ->
            db.selectedAppsListDao().deleteByIdentifierAndPackage(identifier.value, pkg)
        }

        // Verify all identifiers are clean
        allIdentifiers.forEach { identifier ->
            val remaining = db.selectedAppsListDao().getSelectedByIdentifier(identifier.value)
            assertThat(remaining).isEmpty()
        }
    }

    // ===== Upsert REPLACE semantics (re-installing the same app) =====

    @Test
    fun `upsert replaces existing row with same key`() = runBlocking {
        val packageName = "com.example.reinstall"
        // First insert
        db.selectedAppsListDao().upsert(
            SelectedAppItemModel(
                key = "block_new_install_$packageName",
                packageName = packageName,
                appName = "Old Name",
                identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                isSelected = true
            )
        )
        // Re-insert with new app name (simulates reinstall with renamed app)
        db.selectedAppsListDao().upsert(
            SelectedAppItemModel(
                key = "block_new_install_$packageName",
                packageName = packageName,
                appName = "New Name",
                identifier = SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value,
                isSelected = true
            )
        )

        val rows = db.selectedAppsListDao()
            .getSelectedByIdentifier(SelectedAppListIdentifier.BLOCK_NEW_INSTALL_APPS.value)
        assertThat(rows).hasSize(1)
        assertThat(rows[0].appName).isEqualTo("New Name")
    }
}
