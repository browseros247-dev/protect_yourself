# A11Y-OVL-01 — Accessibility-Overlay Block Surface (v1.0.76 / versionCode 76)

**Date:** 2026-07-23 · **Branch:** `fix/a11y-overlay-block-v1.0.76` · **Base:** v1.0.75 branch tip `7c80ba4` (PR for v1.0.75 unmerged at branch time — merge PRs in order)
**Driver:** user report — (1) own accessibility-service settings page still protectable-in-name-only, (2) accessibility service still auto-disabled within 5–15 s. Strategy source: mandated reverse-engineering of **NopoX_1.0.53.apk** (`main:126ab0e`, analyzed read-only; full report: `../../nopox-analysis/NOPOX_REVERSE_ENGINEERING_REPORT.md`).
**Standing order honored:** release APK built and verified **first**, full test suite **after**, commit/push only after both green.

---

## 1. What the reverse engineering proved (input to this round)

| Finding (NopoX 1.0.53, decompiled) | Consequence for us |
|---|---|
| Its block surface is a **full-screen `TYPE_ACCESSIBILITY_OVERLAY` (2032) `WindowManager` window** (`PornBlockPage`), flags `296`, MATCH_PARENT, TRANSLUCENT — added from the service itself; **no block Activity exists in the app at all** | The window TYPE is the whole ballgame: it's Android's sanctioned a11y drawing channel, exempt from the obscuring/consent protection that auto-disables services covered by app Activities (our v1.0.70 kill) |
| PU rule **#12**: node-tree search for the own accessibility-description → cover own a11y detail page with that window (any host, no exemption) | The protection we had to drop in v1.0.70 (and emulate via HOME eviction in v1.0.75) can be restored as a *cover* — safe by window type |
| PU rule **#41**: `Settings$VpnSettingsActivity` → same cover while PU on | Confirms our PU-VPN-01 direction; upgraded to the same surface |
| 5 s `accessibilityServiceConnectCoolDownTime` post-connect; WSS self-heal present but **strictly opportunistic** (no-op without grant) | Adopt the cool-down; no change to our (equivalent) self-heal |

## 2. This round's implementation

| ID | Change |
|---|---|
| A11Y-OVL-01 | NEW `features/blockerPage/utils/A11yBlockOverlay.kt` — sticky singleton overlay: `type = TYPE_ACCESSIBILITY_OVERLAY`, `OVERLAY_FLAGS = FLAG_LAYOUT_IN_SCREEN \| FLAG_NOT_TOUCH_MODAL \| FLAG_NOT_FOCUSABLE` (296), MATCH_PARENT × MATCH_PARENT, TRANSLUCENT, `Animation_Dialog`; inflates the existing `page_porn_block.xml` (rating/why containers hidden for the single-purpose surface); re-`show` while visible swaps the message; **close parity with PornBlockActivity** via shared `CloseGatePolicy` + dwell-seconds DB read + cosmetic countdown + `CATEGORY_HOME` landing; every step try/caught, `show()` returns false on failure; `hide()` idempotent |
| PU-A11Y-PAGE-01 (upgrade) | Own a11y service detail page: **overlay cover is now primary** (`pu_blocked_a11y_page_message`); v1.0.75 HOME-eviction + toast kept only as fallback behind an `addView` failure, and the fallback is suppressed inside the connect cool-down. Detection unchanged (detail-only fingerprint — services list stays reachable; no events while service off ⇒ enabling never obstructed) |
| PU-VPN-01 (upgrade) | VPN settings page: overlay first (`pu_blocked_vpn_settings_message`), activity block screen as fallback |
| A11Y-OVL-COOLDOWN (P-2) | `SERVICE_CONNECT_COOLDOWN_MS = 5_000L`; `serviceConnectCoolDownUntilMs` armed in `onServiceConnected` (reference-equivalent) |
| Teardown | `A11yBlockOverlay.hide()` in `onUnbind` (stale-singleton guard) |
| Untouched | Self-heal/`AccessibilityGuard` (WSS), autostart onboarding row, VPN foreground reconcile, content-blocking `PornBlockActivity` path, the `launchBlockActivity` a11y-screen choke guard (still applies to the Activity path), ANR budgets |

## 3. Why this resolves both reported issues

1. **Own a11y settings page protection:** the page is now truly blocked (covered, toggle unreachable) — the mechanism the field reference has shipped for years; strictly stronger than the v1.0.75 eviction the user found insufficient.
2. **5–15 s auto-disable:** the class of kills caused by *our own windows* over a11y screens is structurally eliminated for the PU surfaces — the cover is a `TYPE_ACCESSIBILITY_OVERLAY`, which the obscuring/consent protection does not count as an app overlay. (Orthogonal causes — OEM background policing without the autostart whitelist, WSS-less self-heal limits — are unchanged and documented; v1.0.74 mitigations remain.)

## 4. Verification

| Step | Result |
|---|---|
| `:app:assembleRelease` (built BEFORE tests) | **BUILD SUCCESSFUL**; 3,275,229 B |
| `apksigner verify` / badging | signature valid; **76 / 1.0.76**, minSdk 26, targetSdk 35 |
| R8 mapping pins | `A11yBlockOverlay` (+`isShowing`), `serviceConnectCoolDownUntilMs`, `evictFromOurA11yServicePage` |
| `:app:testDebugUnitTest` | **493/493 pass, 0 failures/errors/skipped — 40 suites** (`PuSettingsProtectionWiringTest` 7→9; all other suites unchanged and green) |
| New pins | overlay construction (2032/296/MATCH_PARENT/TRANSLUCENT/sticky/no-Activity); overlay-first ordering vs HOME fallback + cool-down gate; VPN overlay-first; unbind hygiene; cool-down arming; strings |

## 5. Risk register

| Risk | Mitigation |
|---|---|
| Window-type assumption proves false on some OEM | Fallback chain intact (overlay add fails → v1.0.75 eviction / activity block); both paths throttled; manual checklist includes the >15 s / >5 min survival probe that previously killed us |
| Sticky overlay stuck (WM quirk) | Close button uses the proven CLOSE-BTN-01 gate (works pre-dwell via toast feedback, post-dwell via close); cleared on unbind; `isShowing` swap avoids double-add |
| Content blocking regression | Content path untouched — `PornBlockActivity` still serves keyword/app blocks; only PU settings surfaces moved |
| Extra per-event cost | None added to the hot path beyond the pre-existing v1.0.75 probes (overlay show only after detection + throttles) |

## 6. Known limitations / follow-ups

1. OEM background-policing kill (vivo-class) without the autostart whitelist remains a distinct, un-fixable-in-app cause — mitigations from v1.0.74 stand.
2. The overlay intentionally hides the rating/motivation containers of `page_porn_block.xml`; porting the full content-block surface to the overlay later would unify both block paths (deliberately out of scope).
3. Screen-record/remote-control bypasses of any visual block are inherent to the platform approach.
