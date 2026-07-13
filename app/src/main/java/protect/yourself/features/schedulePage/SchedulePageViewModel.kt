package protect.yourself.features.schedulePage

import android.app.Application
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
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel

class SchedulePageViewModel(
    application: Application,
    private val db: AppDatabase
) : AndroidViewModel(application) {

    private val _schedules = MutableStateFlow<List<ScheduledRestrictionItemModel>>(emptyList())
    val schedules: StateFlow<List<ScheduledRestrictionItemModel>> = _schedules.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    init {
        observeSchedules()
    }

    private fun observeSchedules() {
        viewModelScope.launch {
            db.scheduledRestrictionDao().observeAll().collect { list ->
                _schedules.value = list
            }
        }
    }

    fun deleteSchedule(restrictionKey: String) {
        viewModelScope.launch {
            db.scheduledRestrictionDao().deleteByKey(restrictionKey)
        }
    }

    fun toggleEnabled(restrictionKey: String, currentEnabled: Boolean) {
        viewModelScope.launch {
            val existing = db.scheduledRestrictionDao().get(restrictionKey) ?: return@launch
            db.scheduledRestrictionDao().upsert(
                existing.copy(isEnabled = !currentEnabled, updatedAt = System.currentTimeMillis())
            )
        }
    }

    fun refresh() {
        _isRefreshing.value = true
        viewModelScope.launch {
            db.scheduledRestrictionDao().observeAll().collect { list ->
                _schedules.value = list
                _isRefreshing.value = false
                return@collect
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    val db = AppDatabase.getInstance(application)
                    return SchedulePageViewModel(application, db) as T
                }
            }
    }
}
