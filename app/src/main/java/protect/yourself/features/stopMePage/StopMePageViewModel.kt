package protect.yourself.features.stopMePage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.stopMeDuration.StopMeDurationItemModel
import protect.yourself.features.blockerPage.service.MyAccessibilityService
import protect.yourself.features.blockerPage.utils.StopMeManager
import timber.log.Timber
import java.util.concurrent.TimeUnit

/**
 * StopMePageViewModel — state management for the in-app Stop Me page.
 *
 * Holds:
 *  - activeSession: currently running instant session (or null)
 *  - scheduledSessions: list of scheduled sessions
 *  - sessionCount: total completed session count
 *  - isOperating: true while a start/stop/cancel operation is in progress
 *  - tick: incremented every second to force recomposition for countdown
 */
class StopMePageViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val stopMeManager = StopMeManager.getInstance(application)

    private val _state = MutableStateFlow(StopMePageState())
    val state: StateFlow<StopMePageState> = _state.asStateFlow()

    init {
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            try {
                // Observe active instant sessions
                db.stopMeDurationDao().observeInstantDurations().collect { instantSessions ->
                    val now = System.currentTimeMillis()
                    val active = instantSessions.firstOrNull { it.endTime > now }
                    _state.update { it.copy(activeSession = active) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe instant sessions")
            }
        }

        viewModelScope.launch {
            try {
                // Observe scheduled sessions
                db.stopMeDurationDao().observeScheduleDurations().collect { schedules ->
                    _state.update { it.copy(scheduledSessions = schedules) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe schedule durations")
            }
        }

        viewModelScope.launch {
            try {
                // Observe session count
                db.stopMeSessionCountDao().observe().collect { count ->
                    _state.update { it.copy(sessionCount = count?.duration ?: 0) }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe session count")
            }
        }
    }

    /**
     * Start an instant Stop Me session with the given duration.
     */
    fun startInstantSession(durationMillis: Long) {
        _state.update { it.copy(isOperating = true) }
        viewModelScope.launch {
            try {
                stopMeManager.startInstantSession(durationMillis)
                // Refresh accessibility service so it starts blocking non-whitelisted apps
                MyAccessibilityService.instance?.setStopMeRunning(true)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to start instant session")
            } finally {
                _state.update { it.copy(isOperating = false) }
            }
        }
    }

    /**
     * Stop the currently active instant session.
     */
    fun stopActiveSession() {
        _state.update { it.copy(isOperating = true) }
        viewModelScope.launch {
            try {
                stopMeManager.stopActiveSession()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to stop active session")
            } finally {
                _state.update { it.copy(isOperating = false) }
            }
        }
    }

    /**
     * Cancel a scheduled session by key.
     */
    fun cancelScheduledSession(key: String) {
        viewModelScope.launch {
            try {
                stopMeManager.cancelScheduledSession(key)
            } catch (t: Throwable) {
                Timber.e(t, "Failed to cancel scheduled session $key")
            }
        }
    }

    /**
     * Refresh the active session — used by UI ticker to update countdown.
     */
    fun refreshActiveSession() {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val active = withContext(Dispatchers.IO) {
                    db.stopMeDurationDao().getActiveInstantSession(now)
                }
                _state.update { it.copy(activeSession = active) }
            } catch (_: Throwable) {}
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StopMePageViewModel(application) as T
                }
            }
    }
}

data class StopMePageState(
    val activeSession: StopMeDurationItemModel? = null,
    val scheduledSessions: List<StopMeDurationItemModel> = emptyList(),
    val sessionCount: Int = 0,
    val isOperating: Boolean = false
) {
    /** Returns the remaining milliseconds if a session is active, else 0. */
    fun remainingMillis(): Long {
        val s = activeSession ?: return 0L
        val now = System.currentTimeMillis()
        return (s.endTime - now).coerceAtLeast(0L)
    }

    /** Returns formatted "MM:SS" remaining time. */
    fun remainingFormatted(): String {
        val ms = remainingMillis()
        if (ms <= 0) return "00:00"
        val mins = TimeUnit.MILLISECONDS.toMinutes(ms)
        val secs = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return "%02d:%02d".format(mins, secs)
    }
}
