package protect.yourself.features.schedulePage

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
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionAppItemModel
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import protect.yourself.domain.schedule.ScheduleEngine
import timber.log.Timber
import java.util.UUID

/**
 * ViewModel for the Schedule tab + Schedule editor.
 *
 * Manages CRUD operations for scheduled restrictions and triggers
 * ScheduleEngine.reevaluateAndApply() after each change so the VPN +
 * Accessibility service are updated immediately.
 */
class SchedulePageViewModel(
    application: Application,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow(SchedulePageState())
    val state: StateFlow<SchedulePageState> = _state.asStateFlow()

    init {
        loadSchedules()
    }

    /**
     * Load all schedules from the DB + their app counts.
     */
    fun loadSchedules() {
        viewModelScope.launch {
            try {
                val rules = db.scheduledRestrictionDao().getAll()
                val displayItems = rules.map { rule ->
                    val appCount = db.scheduledRestrictionAppDao().getAppsForRule(rule.key).size
                    val isActive = checkIfActive(rule)
                    ScheduleDisplayItem(
                        rule = rule,
                        appCount = appCount,
                        isActiveNow = isActive
                    )
                }
                _state.value = _state.value.copy(
                    schedules = displayItems,
                    isLoading = false
                )
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load schedules")
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Check if a rule is currently active (simplified — full evaluation
     * uses ScheduleEvaluator, but for display we can do a quick check).
     */
    private fun checkIfActive(rule: ScheduledRestrictionItemModel): Boolean {
        if (!rule.isEnabled) return false
        val calendar = java.util.Calendar.getInstance()
        val dayOfWeekBit = 1 shl (calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1)
        if (rule.daysOfWeek and dayOfWeekBit == 0) return false
        val nowMinutes = calendar.get(java.util.Calendar.HOUR_OF_DAY) * 60 + calendar.get(java.util.Calendar.MINUTE)
        return if (rule.startTimeMinutes <= rule.endTimeMinutes) {
            nowMinutes in rule.startTimeMinutes until rule.endTimeMinutes
        } else {
            nowMinutes >= rule.startTimeMinutes || nowMinutes < rule.endTimeMinutes
        }
    }

    /**
     * Create a new schedule.
     */
    fun createSchedule(
        name: String,
        type: String,
        startTimeMinutes: Int,
        endTimeMinutes: Int,
        daysOfWeek: Int,
        selectedPackages: List<Pair<String, String>> // (packageName, appName) pairs
    ) {
        viewModelScope.launch {
            try {
                val key = "schedule_${UUID.randomUUID()}"
                val now = System.currentTimeMillis()
                val rule = ScheduledRestrictionItemModel(
                    key = key,
                    name = name,
                    type = type,
                    startTimeMinutes = startTimeMinutes,
                    endTimeMinutes = endTimeMinutes,
                    daysOfWeek = daysOfWeek,
                    isEnabled = true,
                    createdAt = now,
                    updatedAt = now
                )
                db.scheduledRestrictionDao().upsert(rule)
                // Insert app associations
                val appItems = selectedPackages.map { (pkg, appName) ->
                    ScheduledRestrictionAppItemModel(key, pkg, appName)
                }
                if (appItems.isNotEmpty()) {
                    db.scheduledRestrictionAppDao().upsertAll(appItems)
                }
                Timber.i("Schedule created: $key ($name)")
                loadSchedules()
                // Trigger engine to evaluate + apply
                ScheduleEngine.getInstance(getApplication()).reevaluateAndApply()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to create schedule")
            }
        }
    }

    /**
     * Update an existing schedule.
     */
    fun updateSchedule(
        key: String,
        name: String,
        type: String,
        startTimeMinutes: Int,
        endTimeMinutes: Int,
        daysOfWeek: Int,
        selectedPackages: List<Pair<String, String>>
    ) {
        viewModelScope.launch {
            try {
                val existing = db.scheduledRestrictionDao().getByKey(key) ?: return@launch
                val updated = existing.copy(
                    name = name,
                    type = type,
                    startTimeMinutes = startTimeMinutes,
                    endTimeMinutes = endTimeMinutes,
                    daysOfWeek = daysOfWeek,
                    updatedAt = System.currentTimeMillis()
                )
                db.scheduledRestrictionDao().upsert(updated)
                // Replace app associations
                db.scheduledRestrictionAppDao().deleteByRule(key)
                val appItems = selectedPackages.map { (pkg, appName) ->
                    ScheduledRestrictionAppItemModel(key, pkg, appName)
                }
                if (appItems.isNotEmpty()) {
                    db.scheduledRestrictionAppDao().upsertAll(appItems)
                }
                Timber.i("Schedule updated: $key")
                loadSchedules()
                ScheduleEngine.getInstance(getApplication()).reevaluateAndApply()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to update schedule")
            }
        }
    }

    /**
     * Delete a schedule.
     */
    fun deleteSchedule(key: String) {
        viewModelScope.launch {
            try {
                db.scheduledRestrictionAppDao().deleteByRule(key)
                db.scheduledRestrictionDao().deleteByKey(key)
                Timber.i("Schedule deleted: $key")
                loadSchedules()
                ScheduleEngine.getInstance(getApplication()).reevaluateAndApply()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete schedule")
            }
        }
    }

    /**
     * Toggle a schedule's enabled state.
     */
    fun toggleSchedule(key: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                val existing = db.scheduledRestrictionDao().getByKey(key) ?: return@launch
                db.scheduledRestrictionDao().upsert(existing.copy(
                    isEnabled = enabled,
                    updatedAt = System.currentTimeMillis()
                ))
                Timber.i("Schedule toggled: $key → enabled=$enabled")
                loadSchedules()
                ScheduleEngine.getInstance(getApplication()).reevaluateAndApply()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to toggle schedule")
            }
        }
    }

    companion object {
        fun factory(application: Application, db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return SchedulePageViewModel(application, db) as T
                }
            }
    }
}

data class SchedulePageState(
    val schedules: List<ScheduleDisplayItem> = emptyList(),
    val isLoading: Boolean = true
)

data class ScheduleDisplayItem(
    val rule: ScheduledRestrictionItemModel,
    val appCount: Int,
    val isActiveNow: Boolean
)
