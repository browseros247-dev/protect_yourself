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
import protect.yourself.features.streakPage.identifiers.StreakAchievement
import timber.log.Timber
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * StreakPageViewModel — manages streak tracking state.
 *
 * FIXES:
 * 1. Streak calculation now properly counts CONSECUTIVE days since last relapse
 *    (not total active day count)
 * 2. Single Flow collector — no more nested/leaked collectors
 * 3. currentStreakDays recalculated on every Flow emission
 * 4. recordRelapse() no longer calls loadStreakData() (Flow auto-updates)
 */
class StreakPageViewModel(
    private val db: AppDatabase
) : ViewModel() {

    private val _state = MutableStateFlow(StreakPageState())
    val state: StateFlow<StreakPageState> = _state.asStateFlow()

    init {
        observeStreakData()
    }

    /**
     * Single Flow collector that updates ALL state on every emission.
     * No nested collectors — no leaks.
     */
    private fun observeStreakData() {
        viewModelScope.launch {
            try {
                db.streakDatesDao().observeAll().collect { items ->
                    val sorted = items.sortedByDescending { it.startTime }
                    val relapses = sorted.filter { it.type.isNotBlank() }
                    val activeDays = sorted.filter { it.type.isBlank() }

                    // Calculate current streak (consecutive days since last relapse)
                    val currentStreak = calculateConsecutiveStreak(sorted)

                    // Calculate achievements
                    val nextAchievement = StreakAchievement.values()
                        .firstOrNull { it.daysRequired > currentStreak }
                    val unlockedAchievements = StreakAchievement.values()
                        .filter { it.daysRequired <= currentStreak }

                    _state.update {
                        it.copy(
                            currentStreakDays = currentStreak,
                            streakHistory = sorted,
                            relapseCount = relapses.size,
                            activeDayCount = activeDays.size,
                            nextAchievement = nextAchievement,
                            unlockedAchievements = unlockedAchievements,
                            isLoading = false,
                            error = null
                        )
                    }

                    Timber.d("Streak data updated: current=$currentStreak, relapses=${relapses.size}, history=${sorted.size}")
                }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe streak data")
                _state.update { it.copy(isLoading = false, error = t.message) }
            }
        }
    }

    /**
     * Calculate the current consecutive streak.
     *
     * Algorithm:
     * 1. Find the most recent relapse day (type != "")
     * 2. Count days from that relapse to today
     * 3. If no relapses, count days from the first active day to today
     * 4. If no data at all, return 0
     */
    private fun calculateConsecutiveStreak(items: List<StreakDatesItemModel>): Int {
        if (items.isEmpty()) return 0

        val now = System.currentTimeMillis()
        val todayCal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = todayCal.timeInMillis

        // Find most recent relapse
        val sortedByTime = items.sortedByDescending { it.startTime }
        val mostRecentRelapse = sortedByTime.firstOrNull { it.type.isNotBlank() }

        val streakStartDay: Long = if (mostRecentRelapse != null) {
            // Day AFTER the relapse
            val relapseCal = Calendar.getInstance().apply {
                timeInMillis = mostRecentRelapse.startTime
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            relapseCal.add(Calendar.DAY_OF_YEAR, 1)
            relapseCal.timeInMillis
        } else {
            // No relapses — start from the first active day
            val firstActive = sortedByTime.lastOrNull { it.type.isBlank() }
            if (firstActive != null) {
                val firstCal = Calendar.getInstance().apply {
                    timeInMillis = firstActive.startTime
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                firstCal.timeInMillis
            } else {
                // No active days and no relapses
                return 0
            }
        }

        // Calculate days between streak start and today
        val diffMs = todayStart - streakStartDay
        if (diffMs < 0) return 0
        val days = TimeUnit.MILLISECONDS.toDays(diffMs).toInt() + 1 // +1 to include today
        return days.coerceAtLeast(0)
    }

    /**
     * Record a relapse.
     * The Flow collector will auto-update the UI — no need to call loadStreakData().
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

                val item = StreakDatesItemModel(
                    startTime = dayStart,
                    endTime = now,
                    type = type.storageValue,
                    freeText = note
                )
                db.streakDatesDao().upsert(item)
                Timber.i("Relapse recorded: type=${type.storageValue} note=$note")
                // Flow collector will auto-update state — no manual reload needed
            } catch (t: Throwable) {
                Timber.e(t, "Failed to record relapse")
                _state.update { it.copy(error = "Failed to record relapse: ${t.message}") }
            }
        }
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
    val nextAchievement: StreakAchievement? = null,
    val unlockedAchievements: List<StreakAchievement> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
