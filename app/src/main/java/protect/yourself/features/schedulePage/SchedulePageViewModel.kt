package protect.yourself.features.schedulePage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionAppItemModel
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.domain.schedule.ScheduleEngine
import timber.log.Timber
import java.util.UUID

/**
 * ViewModel for the Schedule tab + Schedule editor.
 *
 * Manages CRUD operations for scheduled restrictions and triggers
 * ScheduleEngine.reevaluateAndApply() after each change so the VPN +
 * Accessibility service are updated immediately.
 *
 * VPN DEPENDENCY: Schedules with type "internet" or "both" require VPN
 * to be enabled. This ViewModel checks VPN status before creating or
 * enabling such schedules and emits [ScheduleNavigation.VpnRequired]
 * events so the UI can show a clear warning.
 */
class SchedulePageViewModel(
    application: Application,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(SchedulePageState())
    val state: StateFlow<SchedulePageState> = _state.asStateFlow()

    private val _navigation = MutableSharedFlow<ScheduleNavigation>(extraBufferCapacity = 5)
    val navigation: SharedFlow<ScheduleNavigation> = _navigation.asSharedFlow()

    init {
        loadSchedules()
    }

    /**
     * Load all schedules from the DB + their app counts + VPN status.
     */
    fun loadSchedules() {
        viewModelScope.launch {
            try {
                val rules = db.scheduledRestrictionDao().getAll()
                val vpnEnabled = switchValues.isVpnSwitchOn()
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
                    isLoading = false,
                    isVpnEnabled = vpnEnabled
                )
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load schedules")
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    /**
     * Check if VPN is currently enabled. Called by the UI (ScheduleEditorPage)
     * to show/hide the VPN dependency warning.
     */
    suspend fun isVpnEnabled(): Boolean {
        return try {
            switchValues.isVpnSwitchOn()
        } catch (t: Throwable) {
            false
        }
    }

    /**
     * Create a new schedule.
     *
     * VPN DEPENDENCY CHECK: If the schedule type is "internet" or "both" and
     * VPN is not enabled, emit a [ScheduleNavigation.VpnRequired] event and
     * still create the schedule (so the user can configure it in advance),
     * but the internet blocking won't work until VPN is enabled.
     */
    fun createSchedule(
        name: String,
        type: String,
        startTimeMinutes: Int,
        endTimeMinutes: Int,
        daysOfWeek: Int,
        selectedPackages: List<Pair<String, String>>
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
                val appItems = selectedPackages.map { (pkg, appName) ->
                    ScheduledRestrictionAppItemModel(key, pkg, appName)
                }
                if (appItems.isNotEmpty()) {
                    db.scheduledRestrictionAppDao().upsertAll(appItems)
                }
                Timber.i("Schedule created: $key ($name)")
                loadSchedules()
                ScheduleEngine.getInstance(getApplication()).reevaluateAndApply()

                // VPN DEPENDENCY: if the schedule requires internet blocking
                // and VPN is not enabled, warn the user immediately.
                if ((type == "internet" || type == "both") && !switchValues.isVpnSwitchOn()) {
                    _navigation.emit(ScheduleNavigation.VpnRequired(
                        "This schedule blocks internet, but VPN is not enabled. " +
                            "Enable VPN in Home → Advanced Features → VPN for internet blocking to work."
                    ))
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to create schedule")
                _navigation.emit(ScheduleNavigation.Error("Failed to create schedule: ${t.message}"))
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

                // VPN DEPENDENCY: same check as createSchedule
                if ((type == "internet" || type == "both") && !switchValues.isVpnSwitchOn()) {
                    _navigation.emit(ScheduleNavigation.VpnRequired(
                        "This schedule blocks internet, but VPN is not enabled. " +
                            "Enable VPN in Home → Advanced Features → VPN for internet blocking to work."
                    ))
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to update schedule")
                _navigation.emit(ScheduleNavigation.Error("Failed to update schedule: ${t.message}"))
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
     *
     * VPN DEPENDENCY CHECK: If enabling a schedule with type "internet" or
     * "both" and VPN is not enabled, emit a [ScheduleNavigation.VpnRequired]
     * event. The schedule is still enabled (so it activates when VPN is
     * turned on later), but the user is warned immediately.
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

                // VPN DEPENDENCY: warn if enabling an internet-block schedule without VPN
                if (enabled && (existing.type == "internet" || existing.type == "both") &&
                    !switchValues.isVpnSwitchOn()) {
                    _navigation.emit(ScheduleNavigation.VpnRequired(
                        "'${existing.name}' blocks internet, but VPN is not enabled. " +
                            "Enable VPN in Home → Advanced Features → VPN for internet blocking to work."
                    ))
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to toggle schedule")
            }
        }
    }

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
    val isLoading: Boolean = true,
    val isVpnEnabled: Boolean = false
)

data class ScheduleDisplayItem(
    val rule: ScheduledRestrictionItemModel,
    val appCount: Int,
    val isActiveNow: Boolean
)

/**
 * Navigation events emitted by SchedulePageViewModel.
 */
sealed class ScheduleNavigation {
    /** Emitted when a schedule that requires internet blocking is created/enabled
     *  but VPN is not enabled. The UI shows this as a prominent warning. */
    data class VpnRequired(val message: String) : ScheduleNavigation()
    /** Emitted when an error occurs during CRUD. */
    data class Error(val message: String) : ScheduleNavigation()
}
