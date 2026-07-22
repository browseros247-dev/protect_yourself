package protect.yourself.features.blockerPage.ui

/**
 * CloseGatePolicy — deterministic state machine for the block screen Close
 * button (CLOSE-BTN-01, v1.0.70).
 *
 * ## Problem
 *
 * The old wiring armed the Close button *asynchronously*:
 * `DB read -> IO coroutine -> UI coroutine -> (maybe) setOnClickListener`,
 * plus a second DB round-trip inside `CountDownTimer.onFinish` before the
 * listener was installed, and an `onNewIntent` rebind that nulled the
 * listener and set `isClickable=false` before re-configuring. Any pause,
 * exception, or slow Room query in that chain left the button permanently
 * unresponsive — the reported "Close button has no effect".
 *
 * ## Solution
 *
 * The click listener is installed **synchronously and immediately** when the
 * screen is configured — it is never absent. This policy object decides what
 * a click does:
 *
 *  - Before the dwell elapses → [Click.BLOCKED] carrying the remaining
 *    whole seconds so the UI can give feedback ("available in N s")
 *    instead of a dead, silent button (which read as "broken").
 *  - After the dwell elapses → [Click.CLOSE].
 *
 * A dwell of `<= 0` is armed from the first millisecond.
 *
 * Pure Kotlin (injected clock) — fully unit-testable.
 */
class CloseGatePolicy(
    /** Dwell in seconds (already resolved — v1.0.68 semantics: default 3). */
    private val dwellSeconds: Int,
    /** Monotonic-ish epoch supplier (ms). Inject for tests. */
    private val nowMs: () -> Long = System::currentTimeMillis
) {

    /** Screen configuration instant — the dwell runs from here. */
    private val configuredAtMs: Long = nowMs()

    sealed interface Click {
        /** Click landed during the dwell — ignore, show remaining time. */
        data class Blocked(val remainingSeconds: Long) : Click

        /** Dwell elapsed (or none required) — close the screen. */
        object Close : Click
    }

    /** Remaining dwell in ms (0 once armed). */
    fun remainingDwellMs(atMs: Long = nowMs()): Long {
        val total = dwellSeconds.coerceAtLeast(0) * 1000L
        return (total - (atMs - configuredAtMs)).coerceAtLeast(0L)
    }

    /** True once the dwell has fully elapsed. */
    fun isArmed(atMs: Long = nowMs()): Boolean = remainingDwellMs(atMs) <= 0L

    /** Decide what a Close click should do at the given time. */
    fun onClick(atMs: Long = nowMs()): Click {
        val remaining = remainingDwellMs(atMs)
        return if (remaining <= 0L) {
            Click.Close
        } else {
            // Ceil to whole seconds: 2500ms remaining → "in 3 s" reads better.
            Click.Blocked((remaining + 999) / 1000)
        }
    }
}
