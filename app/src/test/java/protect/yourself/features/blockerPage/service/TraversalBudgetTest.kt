package protect.yourself.features.blockerPage.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * A11Y-ANR-01 (v1.0.71): pins the deterministic time+node ceiling for
 * accessibility tree traversals. Pure JVM — the clock is injected, so no
 * Android runtime is needed.
 */
class TraversalBudgetTest {

    /** Manual monotonic clock — tests advance it explicitly. */
    private class ManualClock(var now: Long = 0L) {
        fun get(): Long = now
        fun advance(ms: Long) { now += ms }
    }

    @Test
    fun `node budget - capped exactly at maxNodes`() {
        val clock = ManualClock()
        val budget = TraversalBudget(maxNodes = 3, maxMillis = 10_000L) { clock.get() }
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.visitedCount).isEqualTo(3)
        assertThat(budget.exhausted).isTrue()
        assertThat(budget.tryVisit()).isFalse()
        // Failed visits do not inflate the counter.
        assertThat(budget.visitedCount).isEqualTo(3)
    }

    @Test
    fun `time budget - exhausted when clock reaches the deadline`() {
        val clock = ManualClock()
        val budget = TraversalBudget(maxNodes = 100, maxMillis = 90L) { clock.get() }
        assertThat(budget.tryVisit()).isTrue()
        clock.advance(89L)
        assertThat(budget.tryVisit()).isTrue()   // 89 ms — still inside the window
        assertThat(budget.remainingMillis()).isEqualTo(1L)
        clock.advance(1L)                         // exactly at deadline
        assertThat(budget.exhausted).isTrue()
        assertThat(budget.tryVisit()).isFalse()
    }

    @Test
    fun `remainingMillis never goes negative`() {
        val clock = ManualClock()
        val budget = TraversalBudget(maxNodes = 10, maxMillis = 50L) { clock.get() }
        clock.advance(500L) // far past the deadline (stalled binder IPC analog)
        assertThat(budget.remainingMillis()).isEqualTo(0L)
        assertThat(budget.exhausted).isTrue()
    }

    @Test
    fun `backwards clock skew never un-exhausts the node budget`() {
        val clock = ManualClock(now = 1_000L)
        val budget = TraversalBudget(maxNodes = 2, maxMillis = 10_000L) { clock.get() }
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.exhausted).isTrue()
        clock.now = 0L // skew backwards
        assertThat(budget.exhausted).isTrue()
        assertThat(budget.tryVisit()).isFalse()
    }

    @Test
    fun `backwards clock skew extends time but never arms early`() {
        val clock = ManualClock(now = 5_000L)
        val budget = TraversalBudget(maxNodes = 10, maxMillis = 90L) { clock.get() }
        clock.now = 4_990L // skew before the start
        // Must NOT be exhausted early — safe direction is "more lenient",
        // the node budget still provides the hard cap.
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.remainingMillis()).isEqualTo(100L)
    }

    @Test
    fun `zero-node budget fails the very first visit`() {
        val clock = ManualClock()
        val budget = TraversalBudget(maxNodes = 0, maxMillis = 1_000L) { clock.get() }
        assertThat(budget.exhausted).isTrue()
        assertThat(budget.tryVisit()).isFalse()
        assertThat(budget.visitedCount).isEqualTo(0)
    }

    @Test
    fun `zero-time budget fails immediately`() {
        val clock = ManualClock()
        val budget = TraversalBudget(maxNodes = 10, maxMillis = 0L) { clock.get() }
        assertThat(budget.exhausted).isTrue()
        assertThat(budget.tryVisit()).isFalse()
    }

    @Test
    fun `budgets combine - node cap reached first stops before time runs out`() {
        val clock = ManualClock()
        val budget = TraversalBudget(maxNodes = 1, maxMillis = 10_000L) { clock.get() }
        assertThat(budget.tryVisit()).isTrue()
        assertThat(budget.exhausted).isTrue()          // node cap — not time
        assertThat(budget.remainingMillis()).isEqualTo(10_000L)
    }
}
