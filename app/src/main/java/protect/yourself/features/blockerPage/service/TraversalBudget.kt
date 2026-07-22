package protect.yourself.features.blockerPage.service

/**
 * A11Y-ANR-01 (v1.0.71): hard **time + node** budget for accessibility
 * node-tree traversals running on the main thread.
 *
 * ## Root cause being fixed
 *
 * Field crash logs (vivo V2206, Android 14, 22 FATAL ANRs in one session)
 * showed the main thread blocked 5–12 s inside `onAccessibilityEvent`:
 * recursive tree walks calling `AccessibilityNodeInfo.getChild()` — each a
 * synchronous **binder IPC** into the remote process. The pre-v1.0.71 guards
 * only counted nodes (300 / 500) with the optimistic assumption of ~2 ms per
 * IPC; under real-world contention (Chrome rendering a heavy page) a single
 * IPC can take 10–50 ms, so 300–500 node walks blew straight past the 5 s
 * ANR threshold. 7 more ANRs blocked in the `getRootInActiveWindow` fetch
 * itself because it was issued **twice per event** (URL + text extraction).
 *
 * ## Design
 *
 * - **Deterministic ceiling**: traversal ends when EITHER the node budget or
 *   the time budget is exhausted — whichever comes first. Time is the
 *   backstop that node counts can't provide when IPC latency is unknown.
 * - **Pure & testable**: the clock is injected, so tests never touch
 *   `SystemClock` (JVM-safe, no Robolectric needed for the pure cases).
 * - **Cheap**: two comparisons per node visited — negligible against the
 *   IPC cost it guards.
 *
 * Exhaustion is *not* an error: callers simply get partial results and the
 * block decision is made on whatever text/URL was found so far (keyword
 * matching works fine on partial page text; the address bar lives near the
 * root, so URL scraping is effectively unaffected).
 */
class TraversalBudget(
    private val maxNodes: Int,
    private val maxMillis: Long,
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }
) {
    private val startedAtMs: Long = clock()
    private val deadlineMs: Long = startedAtMs + maxMillis

    /** Number of nodes successfully visited so far. */
    var visitedCount: Int = 0
        private set

    /**
     * True once either budget is exhausted. Backwards clock movement cannot
     * "un-exhaust" the node budget and only extends the time window, which is
     * the safe direction (never arms early → never ANRs).
     */
    val exhausted: Boolean
        get() = visitedCount >= maxNodes || clock() >= deadlineMs

    /**
     * Attempt to visit one more node. Call **before** doing the expensive
     * per-node work (getChild IPC / text read). Returns false when the
     * budget is spent.
     */
    fun tryVisit(): Boolean {
        if (exhausted) return false
        visitedCount++
        return true
    }

    /** Milliseconds remaining before the time budget expires (never negative). */
    fun remainingMillis(): Long = (deadlineMs - clock()).coerceAtLeast(0L)
}
