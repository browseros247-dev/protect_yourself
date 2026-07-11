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
                    streakDatesCount = db.streakDatesDao().getAll().size,
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
