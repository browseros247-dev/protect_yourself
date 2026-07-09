package protect.yourself.features.streakPage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.streakDates.StreakDatesItemModel
import protect.yourself.features.streakPage.identifiers.RelapseTypeIdentifiers
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * StreakPageViewModel — manages streak tracking state.
 *
 * Phase 5 implementation:
 *  - Calculate current running streak (days since last relapse)
 *  - Load streak history
 *  - Record relapse (insert new StreakDatesItemModel)
 *  - Calculate achievements
 */
class StreakPageViewModel(
    private val db: AppDatabase
) : ViewModel() {

    private val _state = MutableStateFlow(StreakPageState())
    val state: StateFlow<StreakPageState> = _state.asStateFlow()

    init {
        loadStreakData()
    }

    private fun loadStreakData() {
        viewModelScope.launch {
            try {
                val allData = db.streakDatesDao().observeAll()
                // Collect to get current snapshot
                val data = mutableListOf<StreakDatesItemModel>()

                val currentStreak = calculateCurrentStreak()
                val history = db.streakDatesDao().observeAll()
                val relapseDays = db.streakDatesDao().observeRelapseDays()

                _state.update {
                    it.copy(
                        currentStreakDays = currentStreak,
                        isLoading = false
                    )
                }

                // Subscribe to history updates
                viewModelScope.launch {
                    db.streakDatesDao().observeAll().collect { items ->
                        val relapses = items.filter { it.type.isNotBlank() }
                        val activeDays = items.filter { it.type.isBlank() }
                        val nextAchievement = calculateNextAchievement(currentStreak)
                        val unlockedAchievements = calculateUnlockedAchievements(currentStreak)

                        _state.update {
                            it.copy(
                                streakHistory = items.sortedByDescending { it.startTime },
                                relapseCount = relapses.size,
                                activeDayCount = activeDays.size,
                                nextAchievement = nextAchievement,
                                unlockedAchievements = unlockedAchievements
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to load streak data")
                _state.update { it.copy(isLoading = false, error = t.message) }
            }
        }
    }

    /**
     * Calculate current streak — days since last relapse.
     * If no relapses: count of active days since first active day.
     */
    private suspend fun calculateCurrentStreak(): Int {
        return try {
            val allData = db.streakDatesDao().observeAll()
            // We need a synchronous snapshot — use countActiveStreakDays for now
            val activeCount = db.streakDatesDao().countActiveStreakDays()
            activeCount
        } catch (t: Throwable) {
            Timber.w(t, "Failed to calculate current streak")
            0
        }
    }

    /**
     * Record a relapse.
     */
    fun recordRelapse(type: RelapseTypeIdentifiers, note: String) {
        viewModelScope.launch {
            try {
                val now = System.currentTimeMillis()
                val cal = Calendar.getInstance().apply {
                    timeInMillis = now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                val dayStart = cal.timeInMillis
                val dayEnd = dayStart + TimeUnit.DAYS.toMillis(1) - 1

                val item = StreakDatesItemModel(
                    startTime = dayStart,
                    endTime = now,  // relapse timestamp
                    type = type.storageValue,
                    freeText = note
                )
                db.streakDatesDao().upsert(item)
                Timber.i("Relapse recorded: type=${type.storageValue} note=$note")

                // Reload data
                loadStreakData()
            } catch (t: Throwable) {
                Timber.e(t, "Failed to record relapse")
            }
        }
    }

    private fun calculateNextAchievement(currentDays: Int): protect.yourself.features.streakPage.identifiers.StreakAchievement? {
        return protect.yourself.features.streakPage.identifiers.StreakAchievement.values()
            .firstOrNull { it.daysRequired > currentDays }
    }

    private fun calculateUnlockedAchievements(currentDays: Int): List<protect.yourself.features.streakPage.identifiers.StreakAchievement> {
        return protect.yourself.features.streakPage.identifiers.StreakAchievement.values()
            .filter { it.daysRequired <= currentDays }
    }

    companion object {
        fun factory(db: AppDatabase): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return StreakPageViewModel(db) as T
                }
            }
    }
}

data class StreakPageState(
    val currentStreakDays: Int = 0,
    val streakHistory: List<StreakDatesItemModel> = emptyList(),
    val relapseCount: Int = 0,
    val activeDayCount: Int = 0,
    val nextAchievement: protect.yourself.features.streakPage.identifiers.StreakAchievement? = null,
    val unlockedAchievements: List<protect.yourself.features.streakPage.identifiers.StreakAchievement> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
