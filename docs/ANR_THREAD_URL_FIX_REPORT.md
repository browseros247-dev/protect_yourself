# ANR + Thread Violation + Chrome URL Fix Report — v1.0.58

## Executive Summary

This release fixes 3 critical issues identified in the crash log analysis:
1. **KillTimer CalledFromWrongThreadException** — background thread calling `performGlobalAction`
2. **Chrome URL extraction returning null** — missing `contentDescription` fallback
3. **ANR 5,002–7,346ms** — unbounded node-tree traversal on main thread

All fixes are verified against the NopoX 1.0.53 reference APK. The release APK
v1.0.58 builds successfully and all unit tests pass.

---

## Bug 1: KillTimer CalledFromWrongThreadException (THREAD-01, CRITICAL)

**File**: `BlockOverlayManager.kt` → `KillTimer`

**Symptom**:
```
E ViewRootImpl: Accessibility content change on non-UI thread.
android.view.ViewRootImpl$CalledFromWrongThreadException:
Only the original thread that created a view hierarchy can touch its views.
```

**Root cause**: The `KillTimer` class uses `java.util.Timer` (background thread)
to call `service.performGlobalAction(GLOBAL_ACTION_HOME)` and
`GLOBAL_ACTION_BACK`. On Android 14 (API 34), `performGlobalAction` internally
triggers accessibility content change events that `ViewRootImpl` validates
against the thread that created the view hierarchy (the main thread). Calling
these from a background thread throws `CalledFromWrongThreadException`.

**NopoX 1.0.53 reference**: NopoX has the **exact same bug** — `PornBlockPage.java`
line 254 uses `TimersKt.timer().scheduleAtFixedRate(...)` to call
`performGlobalAction` from a background thread. This was tolerated on older
Android versions but Android 14 enforces the thread check strictly.

**Fix**: Dispatch all `performGlobalAction` calls to the main thread via
`Handler(Looper.getMainLooper()).post { ... }`. This is an improvement over
NopoX 1.0.53.

---

## Bug 2: Chrome URL Extraction Returns Null (URL-01, MODERATE)

**File**: `MyAccessibilityService.kt` → `extractUrlFromEvent` + `findUrlInNode`

**Symptom**:
```
PB-04: browser pkg=com.android.chrome detected
but URL extraction returned null — url bar view id may have changed
```

**Root cause**: Recent Chrome versions (2024+) sometimes expose the URL via
`contentDescription` instead of `text` — especially when the user is typing
in the address bar or when the page is still loading. The code only checked
`node.text`, never `node.contentDescription`.

**NopoX 1.0.53 reference**: NopoX also only checks `text` (decompiled
`checkPornUrlSearch`). The rebuild inherited this, but Chrome has since changed.

**Fix**: Added `contentDescription` fallback in both:
1. `extractUrlFromEvent` — after checking `node.text`, checks `node.contentDescription`
2. `findUrlInNode` — recursive search now checks both `text` and `contentDescription`

---

## Bug 3: ANR — Main Thread Blocked 5,002–7,346ms (ANR-01, CRITICAL)

**File**: `MyAccessibilityService.kt` → `collectText` + `findUrlInNode`

**Symptom**:
```
E AnrWatchdog: ANR detected — main thread blocked for 5004ms
E AnrWatchdog: ANR detected — main thread blocked for 7346ms
```

**Root cause**: `rootInActiveWindow` and node-tree traversal
(`getChild()`, `findAccessibilityNodeInfosByViewId()`) are **blocking IPC calls**
that run on the main thread. On complex pages (Chrome with many tabs), the
recursive traversal can visit thousands of nodes, each requiring an IPC
round-trip — blocking the main thread for 5+ seconds.

**NopoX 1.0.53 reference**: NopoX has the same architecture (all accessibility
event processing on the main thread), but NopoX doesn't have an ANR watchdog
so the issue was invisible. The rebuild's `AnrWatchdog` made it visible.

**Fix**: Added hard node-count limits to prevent unbounded traversal:
- `MAX_URL_SEARCH_NODES = 300` — `findUrlInNode` bails out after 300 nodes
  (~600ms worst case at ~2ms IPC per node)
- `MAX_TEXT_COLLECTION_NODES = 500` — `collectText` bails out after 500 nodes
  (~1s worst case)

Both limits are well under the 5s ANR threshold. The block decision is based
on keyword matching, which works fine with partial text/URL — so truncating
the traversal is an acceptable tradeoff.

---

## Files Changed

| File | Change |
|------|--------|
| `app/build.gradle.kts` | Version bump 1.0.55 → 1.0.58 |
| `app/src/main/java/.../BlockOverlayManager.kt` | THREAD-01: KillTimer dispatches to main thread |
| `app/src/main/java/.../MyAccessibilityService.kt` | URL-01: contentDescription fallback + ANR-01: node-count limits |

---

## Verification Checklist

- [x] KillTimer `performGlobalAction` calls dispatched to main thread (THREAD-01)
- [x] `extractUrlFromEvent` checks `contentDescription` fallback (URL-01)
- [x] `findUrlInNode` checks `contentDescription` fallback (URL-01)
- [x] `findUrlInNode` has `MAX_URL_SEARCH_NODES = 300` limit (ANR-01)
- [x] `collectText` has `MAX_TEXT_COLLECTION_NODES = 500` limit (ANR-01)
- [x] Release APK v1.0.58 builds successfully
- [x] All existing tests pass (no regressions)
- [x] Changes are on feature branch, not main
- [x] Verified against NopoX 1.0.53 reference APK (decompiled via jadx)
