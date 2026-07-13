package protect.yourself.domain.schedule

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import protect.yourself.database.scheduledRestrictions.ScheduledRestrictionItemModel
import java.util.Calendar

/**
 * Unit tests for [ScheduleEvaluator] — the pure function that determines
 * which apps should be blocked at a given timestamp.
 *
 * Tests cover:
 *  - Same-day schedules (9:00 → 17:00)
 *  - Cross-midnight schedules (22:00 → 6:00)
 *  - Day-of-week filtering
 *  - Multiple overlapping rules (union semantics)
 *  - Disabled rules
 *  - Rules with no apps
 *  - All three restriction types (internet, launch, both)
 */
class ScheduleEvaluatorTest {

    private fun makeRule(
        key: String = "rule1",
        type: String = "internet",
        startMin: Int = 540,   // 9:00 AM
        endMin: Int = 1020,    // 5:00 PM
        daysOfWeek: Int = 127, // every day
        isEnabled: Boolean = true
    ): ScheduledRestrictionItemModel {
        return ScheduledRestrictionItemModel(
            key = key,
            name = "Test Rule",
            type = type,
            startTimeMinutes = startMin,
            endTimeMinutes = endMin,
            daysOfWeek = daysOfWeek,
            isEnabled = isEnabled
        )
    }

    private fun makeTimestamp(hour: Int, minute: Int = 0, dayOfWeek: Int = Calendar.MONDAY): Long {
        val cal = Calendar.getInstance().apply {
            set(Calendar.YEAR, 2026)
            set(Calendar.MONTH, Calendar.JULY)
            set(Calendar.DAY_OF_WEEK, dayOfWeek)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    // ===== Same-day schedule tests =====

    @Test
    fun `same-day schedule active during window`() {
        val rule = makeRule(startMin = 540, endMin = 1020) // 9:00-17:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(12, 0) // noon
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.example.app")
    }

    @Test
    fun `same-day schedule inactive before window`() {
        val rule = makeRule(startMin = 540, endMin = 1020) // 9:00-17:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(7, 0) // 7 AM
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).doesNotContain("com.example.app")
    }

    @Test
    fun `same-day schedule inactive after window`() {
        val rule = makeRule(startMin = 540, endMin = 1020) // 9:00-17:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(18, 0) // 6 PM
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).doesNotContain("com.example.app")
    }

    @Test
    fun `same-day schedule active at exact start time`() {
        val rule = makeRule(startMin = 540, endMin = 1020) // 9:00-17:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(9, 0) // exactly 9:00 AM
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.example.app")
    }

    @Test
    fun `same-day schedule inactive at exact end time`() {
        val rule = makeRule(startMin = 540, endMin = 1020) // 9:00-17:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(17, 0) // exactly 5:00 PM
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).doesNotContain("com.example.app")
    }

    // ===== Cross-midnight schedule tests =====

    @Test
    fun `cross-midnight schedule active before midnight`() {
        val rule = makeRule(startMin = 1320, endMin = 360) // 22:00-6:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(23, 0) // 11 PM
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.example.app")
    }

    @Test
    fun `cross-midnight schedule active after midnight`() {
        val rule = makeRule(startMin = 1320, endMin = 360) // 22:00-6:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(3, 0) // 3 AM
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.example.app")
    }

    @Test
    fun `cross-midnight schedule inactive during day`() {
        val rule = makeRule(startMin = 1320, endMin = 360) // 22:00-6:00
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(12, 0) // noon
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).doesNotContain("com.example.app")
    }

    // ===== Day-of-week tests =====

    @Test
    fun `schedule inactive on wrong day of week`() {
        // Only Monday (bit 1 = 2)
        val rule = makeRule(daysOfWeek = 2)
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(12, 0, Calendar.TUESDAY) // Tuesday
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).doesNotContain("com.example.app")
    }

    @Test
    fun `schedule active on correct day of week`() {
        // Only Monday (bit 1 = 2)
        val rule = makeRule(daysOfWeek = 2)
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(12, 0, Calendar.MONDAY) // Monday
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.example.app")
    }

    @Test
    fun `schedule with no days selected is never active`() {
        val rule = makeRule(daysOfWeek = 0)
        val apps = mapOf("rule1" to listOf("com.example.app"))
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).isEmpty()
    }

    // ===== Type tests =====

    @Test
    fun `internet type blocks internet only`() {
        val rule = makeRule(type = "internet")
        val apps = mapOf("rule1" to listOf("com.app"))
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.app")
        assertThat(result.launchBlockedPackages).doesNotContain("com.app")
    }

    @Test
    fun `launch type blocks launch only`() {
        val rule = makeRule(type = "launch")
        val apps = mapOf("rule1" to listOf("com.app"))
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.launchBlockedPackages).contains("com.app")
        assertThat(result.internetBlockedPackages).doesNotContain("com.app")
    }

    @Test
    fun `both type blocks internet and launch`() {
        val rule = makeRule(type = "both")
        val apps = mapOf("rule1" to listOf("com.app"))
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.app")
        assertThat(result.launchBlockedPackages).contains("com.app")
    }

    // ===== Multiple rules (union semantics) =====

    @Test
    fun `multiple active rules targeting same app produce union`() {
        val rule1 = makeRule(key = "r1", type = "internet")
        val rule2 = makeRule(key = "r2", type = "launch")
        val apps = mapOf(
            "r1" to listOf("com.app"),
            "r2" to listOf("com.app")
        )
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule1, rule2), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.app")
        assertThat(result.launchBlockedPackages).contains("com.app")
    }

    @Test
    fun `multiple active rules targeting different apps`() {
        val rule1 = makeRule(key = "r1", type = "internet")
        val rule2 = makeRule(key = "r2", type = "launch")
        val apps = mapOf(
            "r1" to listOf("com.app1"),
            "r2" to listOf("com.app2")
        )
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule1, rule2), apps, now)
        assertThat(result.internetBlockedPackages).containsExactly("com.app1")
        assertThat(result.launchBlockedPackages).containsExactly("com.app2")
    }

    // ===== Disabled rules =====

    @Test
    fun `disabled rule is ignored`() {
        val rule = makeRule(isEnabled = false)
        val apps = mapOf("rule1" to listOf("com.app"))
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).isEmpty()
    }

    // ===== Edge cases =====

    @Test
    fun `rule with no apps produces no blocks`() {
        val rule = makeRule()
        val apps = mapOf("rule1" to emptyList<String>())
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).isEmpty()
    }

    @Test
    fun `rule with missing app list produces no blocks`() {
        val rule = makeRule()
        val apps = emptyMap<String, List<String>>()
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).isEmpty()
    }

    @Test
    fun `empty rules list produces no blocks`() {
        val now = makeTimestamp(12, 0)
        val result = ScheduleEvaluator.evaluate(emptyList(), emptyMap(), now)
        assertThat(result.internetBlockedPackages).isEmpty()
        assertThat(result.launchBlockedPackages).isEmpty()
    }

    @Test
    fun `all-day schedule (0 to 1439) is active all day`() {
        val rule = makeRule(startMin = 0, endMin = 1439)
        val apps = mapOf("rule1" to listOf("com.app"))
        val now = makeTimestamp(0, 0) // midnight
        val result = ScheduleEvaluator.evaluate(listOf(rule), apps, now)
        assertThat(result.internetBlockedPackages).contains("com.app")
    }
}
