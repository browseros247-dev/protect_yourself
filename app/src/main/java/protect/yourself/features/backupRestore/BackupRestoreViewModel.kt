package protect.yourself.features.backupRestore

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import timber.log.Timber

/**
 * BackupRestoreViewModel — state management for the Backup/Restore page.
 *
 * Holds:
 *  - isOperating: true while an export/import is in progress (disables buttons)
 *  - progress: live progress updates from BackupManager
 *  - lastResult: the result of the last operation (Success or Error)
 *  - showImportConfirm + pendingImportUri: confirmation dialog state
 *  - stats: current DB row counts (shown in the "What's included" card)
 */
class BackupRestoreViewModel(
    application: Application,
    private val backupManager: BackupManager
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(BackupRestoreState())
    val state: StateFlow<BackupRestoreState> = _state.asStateFlow()

    val progress: StateFlow<BackupProgress> = backupManager.progress

    init {
        loadStats()
    }

    /**
     * Load current DB row counts for display in the "What's included" card.
     */
    private fun loadStats() {
        viewModelScope.launch {
            try {
                val db = AppDatabase.getInstance(getApplication())
                val stats = BackupStats(
                    switchCount = db.switchStatusDao().getAll().size,
                    keywordCount = db.selectedKeywordDao().getAll().size,
                    appCount = db.selectedAppsListDao().getAll().size,
                    blockScreenCountCount = db.blockScreenCountDao().getAll().size,
                    pendingRequestCount = db.pendingRequestDao().getAll().size,
                    stopMeDurationCount = db.stopMeDurationDao().getAll().size,
                    stopMeSessionCountCount = db.stopMeSessionCountDao().getAll().size,
                    vpnCustomDnsCount = db.vpnCustomDnsDao().getAll().size,
                    totalRows = 0  // not shown in UI; calculated if needed
                )
                _state.update { it.copy(stats = stats) }
            } catch (t: Throwable) {
                Timber.w(t, "Failed to load backup stats")
            }
        }
    }

    /**
     * Trigger export to the user-picked URI.
     */
    fun exportToUri(uri: Uri) {
        _state.update { it.copy(isOperating = true, lastResult = null) }
        viewModelScope.launch {
            val result = backupManager.exportToUri(uri)
            _state.update {
                it.copy(
                    isOperating = false,
                    lastResult = result
                )
            }
            // Reload stats after export (counts don't change but be safe)
            loadStats()
        }
    }

    /**
     * Trigger import from the user-picked URI.
     */
    fun importFromUri(uri: Uri) {
        _state.update { it.copy(isOperating = true, lastResult = null) }
        viewModelScope.launch {
            val result = backupManager.importFromUri(uri)
            _state.update {
                it.copy(
                    isOperating = false,
                    lastResult = result
                )
            }
            // Reload stats after import (counts will change)
            loadStats()

            // If import succeeded, refresh the accessibility service's cached config
            // so blocking reflects the restored switch states
            if (result is BackupResult.Success) {
                try {
                    protect.yourself.features.blockerPage.service.MyAccessibilityService.instance
                        ?.refreshBlockingConfig()
                } catch (_: Throwable) {}
                // VPN-RESTORE-03 fix (v1.0.64): reconcile the live VPN service
                // with the RESTORED state. Previously only the accessibility
                // service was refreshed — a backup with VPN_SWITCH=ON restored
                // onto a device where the VPN wasn't running left protection
                // silently off (and vice versa) until the next reboot or VPN
                // page visit. Restored VPN_CONNECTION_TYPE / custom DNS presets
                // also only took effect on the next VPN session. The app is in
                // the foreground here, so starting the FGS directly is allowed
                // on every API level.
                reconcileVpnWithRestoredState()
            }
        }
    }

    /**
     * VPN-RESTORE-03: aligns the running VPN service with the just-restored
     * VPN_SWITCH / VPN_CONNECTION_TYPE / custom-DNS preset state:
     *
     *  - switch ON  + not running → start (if consent still granted; if the
     *    restored switch is ON but consent is missing on THIS device, sync
     *    the switch OFF so the UI reflects reality);
     *  - switch OFF + running     → stop;
     *  - switch ON  + running     → restart so the restored mode/DNS presets
     *    apply immediately;
     *  - switch OFF + not running → nothing to do.
     */
    private fun reconcileVpnWithRestoredState() {
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val db = AppDatabase.getInstance(ctx)
                val switchValues = protect.yourself.database.switchStatus
                    .SwitchStatusValues(db.switchStatusDao())
                val vpnOn = switchValues.isVpnSwitchOn()
                val serviceState = protect.yourself.features.blockerPage.service
                    .MyVpnService.observableVpnState
                val runningOrStarting =
                    protect.yourself.features.blockerPage.service.MyVpnService.isRunning() ||
                    serviceState == protect.yourself.features.blockerPage.service.MyVpnService.VpnState.CONNECTING ||
                    serviceState == protect.yourself.features.blockerPage.service.MyVpnService.VpnState.CONNECTED

                when {
                    vpnOn && !runningOrStarting -> {
                        if (android.net.VpnService.prepare(ctx) == null) {
                            Timber.i("VPN-RESTORE-03: restored VPN_SWITCH=ON, service down — starting VPN")
                            protect.yourself.features.blockerPage.service.MyVpnService.start(ctx)
                        } else {
                            // Consent doesn't exist on this device (VPN consent
                            // is per-device and is NOT portable via backup).
                            Timber.w("VPN-RESTORE-03: restored VPN_SWITCH=ON but no VPN consent on this device — syncing switch OFF")
                            switchValues.storeSwitchStatus(
                                protect.yourself.database.switchStatus.SwitchIdentifier.VPN_SWITCH,
                                false
                            )
                        }
                    }
                    !vpnOn && runningOrStarting -> {
                        Timber.i("VPN-RESTORE-03: restored VPN_SWITCH=OFF, service running — stopping VPN")
                        protect.yourself.features.blockerPage.service.MyVpnService.stop(ctx)
                    }
                    vpnOn && runningOrStarting -> {
                        // Apply restored mode/DNS selection to the live tunnel.
                        Timber.i("VPN-RESTORE-03: restarting VPN to apply restored mode/DNS state")
                        protect.yourself.features.blockerPage.service.MyVpnService.restart(ctx)
                    }
                    else -> {
                        Timber.d("VPN-RESTORE-03: VPN already matches restored state — nothing to do")
                    }
                }
            } catch (t: Throwable) {
                Timber.w(t, "VPN-RESTORE-03: post-restore VPN reconcile failed (non-fatal)")
            }
        }
    }

    /**
     * Show the import confirmation dialog.
     */
    fun showImportConfirmation(uri: Uri) {
        _state.update {
            it.copy(
                showImportConfirm = true,
                pendingImportUri = uri
            )
        }
    }

    /**
     * Cancel the import confirmation dialog.
     */
    fun cancelImportConfirmation() {
        _state.update {
            it.copy(
                showImportConfirm = false,
                pendingImportUri = null
            )
        }
    }

    /**
     * Report that the user cancelled a SAF picker (CreateDocument or
     * OpenDocument) by backing out without picking a file. Surfaces a
     * non-fatal "Cancelled" dialog so the user knows the operation was
     * not silently ignored.
     */
    fun reportCancelled() {
        _state.update {
            it.copy(lastResult = BackupResult.Error.Cancelled)
        }
    }

    /**
     * Clear the last result (after UI has shown it).
     */
    fun clearResult() {
        _state.update { it.copy(lastResult = null) }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val backupManager = BackupManager(application)
                    return BackupRestoreViewModel(application, backupManager) as T
                }
            }
    }
}

data class BackupRestoreState(
    val isOperating: Boolean = false,
    val lastResult: BackupResult? = null,
    val showImportConfirm: Boolean = false,
    val pendingImportUri: Uri? = null,
    val stats: BackupStats = BackupStats()
)
