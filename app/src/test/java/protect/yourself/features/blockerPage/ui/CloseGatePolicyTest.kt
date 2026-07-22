package protect.yourself.features.blockerPage.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for [CloseGatePolicy] (CLOSE-BTN-01, v1.0.70).
 *
 * Pins the semantics of the block-screen Close control:
 *  - deterministic, time-governed — no asynchronous arming chain;
 *  - clicks during the dwell are NEVER silent (feedback with remaining time);
 *  - clicks after the dwell ALWAYS close;
 *  - zero/negative dwell is armed immediately.
 */
class CloseGatePolicyTest {

    private class FakeClock(var now: Long = 1_000L) {
        fun tick(deltaMs: Long) { now += deltaMs }
    }

    // ============================================================================
    // Arming
    // ============================================================================

    @Test
    fun `zero dwell is armed immediately`() {
        val clock = FakeClock()
        val gate = CloseGatePolicy(0) { clock.now }
        assertThat(gate.isArmed()).isTrue()
        assertThat(gate.onClick()).isEqualTo(CloseGatePolicy.Click.Close)
    }

    @Test
    fun `negative dwell degrades to immediate close (never traps the user)`() {
        val clock = FakeClock()
        val gate = CloseGatePolicy(-5) { clock.now }
        assertThat(gate.isArmed()).isTrue()
        assertThat(gate.onClick()).isEqualTo(CloseGatePolicy.Click.Close)
    }

    @Test
    fun `dwell boundary - blocked just before, closing exactly at the boundary`() {
        val clock = FakeClock()
        val gate = CloseGatePolicy(3) { clock.now }

        clock.tick(2_999)
        val justBefore = gate.onClick()
        assertThat(justBefore).isInstanceOf(CloseGatePolicy.Click.Blocked::class.java)
        assertThat((justBefore as CloseGatePolicy.Click.Blocked).remainingSeconds).isEqualTo(1)

        clock.tick(1) // exactly 3_000 ms elapsed
        assertThat(gate.isArmed()).isTrue()
        assertThat(gate.onClick()).isEqualTo(CloseGatePolicy.Click.Close)
    }

    // ============================================================================
    // Feedback semantics (the "button feels dead" fix)
    // ============================================================================

    @Test
    fun `clicks during dwell report remaining whole seconds (ceil)`() {
        val clock = FakeClock()
        val gate = CloseGatePolicy(3) { clock.now }

        // At 400ms in → 2600ms remaining → ceil = 3s (matches the label style).
        clock.tick(400)
        assertThat((gate.onClick() as CloseGatePolicy.Click.Blocked).remainingSeconds).isEqualTo(3)

        // At 1100ms in → 1900ms remaining → ceil = 2s.
        clock.tick(700)
        assertThat((gate.onClick() as CloseGatePolicy.Click.Blocked).remainingSeconds).isEqualTo(2)

        // At 2100ms in → 900ms remaining → ceil = 1s.
        clock.tick(1_000)
        assertThat((gate.onClick() as CloseGatePolicy.Click.Blocked).remainingSeconds).isEqualTo(1)
    }

    @Test
    fun `repeated clicks during dwell stay consistent (no state corruption)`() {
        val clock = FakeClock()
        val gate = CloseGatePolicy(3) { clock.now }
        repeat(10) { assertThat(gate.onClick()).isInstanceOf(CloseGatePolicy.Click.Blocked::class.java) }
        clock.tick(3_000)
        repeat(10) { assertThat(gate.onClick()).isEqualTo(CloseGatePolicy.Click.Close) }
    }

    // ============================================================================
    // Time-source robustness
    // ============================================================================

    @Test
    fun `clock skew backwards clamps to positive remaining rather than arming early`() {
        val clock = FakeClock(now = 10_000L)
        val gate = CloseGatePolicy(3) { clock.now }
        // Clock jumps backwards 60s (NTP correction) — remaining would be
        // negative-clamped to 60_003ms; the user must still wait, no early arm.
        clock.now = -50_000L
        val click = gate.onClick()
        assertThat(click).isInstanceOf(CloseGatePolicy.Click.Blocked::class.java)
        assertThat((click as CloseGatePolicy.Click.Blocked).remainingSeconds).isAtLeast(3)
    }

    @Test
    fun `remainingDwellMs never goes negative`() {
        val clock = FakeClock()
        val gate = CloseGatePolicy(3) { clock.now }
        clock.tick(60_000)
        assertThat(gate.remainingDwellMs()).isEqualTo(0)
        assertThat(gate.isArmed()).isTrue()
    }
}
