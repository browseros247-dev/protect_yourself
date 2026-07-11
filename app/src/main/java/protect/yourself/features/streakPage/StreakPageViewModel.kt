package protect.yourself.features.streakPage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
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
        ensureTodayActiveDay()
    }

    /**
     * BUG FIX: Auto-create an active day entry for today if none exists.
     *
     * NopoX does this automatically — every time the app opens, it inserts
     * an active day record (type = "") for today. Without this, the streak
     * counter stays at 0 forever because there's no data to count from.
     *
     * Uses OnConflictStrategy.REPLACE so if today's entry already exists
     * (either as active day or relapse), it won't duplicate.
     * However, we first CHECK if today already has a relapse — if so,
     * we don't overwrite it.
     */
    private fun ensureTodayActiveDay() {
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

                // Check if today already has an entry
                val allData = db.streakDatesDao().getAll()
                val todayEntry = allData.find { it.startTime == dayStart }

                if (todayEntry == null) {
                    // No entry for today — create an active day
                    val item = StreakDatesItemModel(
                        startTime = dayStart,
                        endTime = now,
                        type = "",
                        freeText = ""
                    )
                    db.streakDatesDao().upsert(item)
                    Timber.i("Auto-created active day entry for today: $dayStart")
                }
            } catch (t: Throwable) {
                Timber.w(t, "Failed to ensure today's active day")
            }
        }
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

                    // BUG FIX: Calculate best (longest) streak ever
                    val bestStreak = calculateBestStreak(sorted)

                    // Calculate achievements
                    val nextAchievement = StreakAchievement.values()
                        .firstOrNull { it.daysRequired > currentStreak }
                    val unlockedAchievements = StreakAchievement.values()
                        .filter { it.daysRequired <= currentStreak }

                    _state.update {
                        it.copy(
                            currentStreakDays = currentStreak,
                            bestStreakDays = bestStreak,
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
            } catch (t: CancellationException) {
                // Normal control flow — viewModelScope was cancelled (user
                // navigated away). Re-throw to preserve structured concurrency.
                // The previous code caught this via `catch (t: Throwable)` and
                // logged it as "Failed to observe streak data", which spammed
                // the crash log with false-positive ERROR entries.
                throw t
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
     * BUG FIX: Calculate the best (longest) streak ever achieved.
     *
     * Iterates through the history, splitting on relapses, and finds
     * the longest gap between two relapses (or from first active day
     * to most recent relapse).
     *
     * NopoX tracks this as "best streak" — shown alongside current streak.
     */
    private fun calculateBestStreak(items: List<StreakDatesItemModel>): Int {
        if (items.isEmpty()) return 0

        val sorted = items.sortedBy { it.startTime }
        var bestStreak = 0
        var streakStart: Long? = null

        for (item in sorted) {
            if (item.type.isBlank()) {
                // Active day — start streak if not already started
                if (streakStart == null) {
                    streakStart = item.startTime
                }
            } else {
                // Relapse — end the current streak
                if (streakStart != null) {
                    val cal1 = Calendar.getInstance().apply {
                        timeInMillis = streakStart
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val cal2 = Calendar.getInstance().apply {
                        timeInMillis = item.startTime
                        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
                        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                    }
                    val diffMs = cal2.timeInMillis - cal1.timeInMillis
                    if (diffMs > 0) {
                        val days = TimeUnit.MILLISECONDS.toDays(diffMs).toInt()
                        if (days > bestStreak) bestStreak = days
                    }
                    streakStart = null
                }
            }
        }

        // If streak is still active (no relapse after last active day), count up to today
        if (streakStart != null) {
            val current = calculateConsecutiveStreak(items)
            if (current > bestStreak) bestStreak = current
        }

        return bestStreak
    }

    /**
     * Record a relapse.
     *
     * BUG FIX: If an active day entry already exists for today (startTime = dayStart),
     * we UPDATE it to mark it as a relapse instead of inserting a new record.
     * The original code used OnConflictStrategy.REPLACE which would work, but
     * only if the startTime matches exactly. We also preserve the original
     * startTime to maintain the primary key.
     *
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

                // Check if today already has an entry (active day or relapse)
                val allData = db.streakDatesDao().getAll()
                val todayEntry = allData.find { it.startTime == dayStart }

                val item = if (todayEntry != null) {
                    // Update existing entry — preserve startTime (PK)
                    StreakDatesItemModel(
                        startTime = todayEntry.startTime,
                        endTime = now,
                        type = type.storageValue,
                        freeText = note
                    )
                } else {
                    // No entry for today — create new relapse entry
                    StreakDatesItemModel(
                        startTime = dayStart,
                        endTime = now,
                        type = type.storageValue,
                        freeText = note
                    )
                }
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
    val bestStreakDays: Int = 0,
    val streakHistory: List<StreakDatesItemModel> = emptyList(),
    val relapseCount: Int = 0,
    val activeDayCount: Int = 0,
    val nextAchievement: StreakAchievement? = null,
    val unlockedAchievements: List<StreakAchievement> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)
