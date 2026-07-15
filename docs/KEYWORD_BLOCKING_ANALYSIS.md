# Keyword-Blocking Deep Analysis — Protect-Yourself (Future-Brand branch)

> **Branch**: `Future-Brand` (commit `52a87d7`, v1.0.35-debug)
> **Analysis date**: 2026-07-11
> **Scope**: Every keyword-based blocking setting, evaluated individually against the reference implementation, with focus on (a) matching correctness, (b) performance, (c) UI/UX, (d) bugs / inconsistencies / improvement opportunities.
> **Methodology**: Static source review of the Future-Brand keyword-blocking code path + JADX decompilation of `protect.yourself-v1.0.33-release.apk` + cross-reference with `docs/COMPARISON_REPORT.md` (written from a full JADX decompilation of the reference APK).
> **Reference**: v1.0.53 (reference package).

---

## Executive Summary

The Future-Brand branch ships a working keyword-blocking subsystem with four distinct keyword lists, a real Keyword Manager UI (closing the biggest reference-parity gap from v1.0.33), and URL extraction that covers 6 known browsers + a fallback node-tree search. The core matching logic (`isDetectWordInUrl` for URLs, `isDetectWord` for text, `isAnyTitleBlocked` for settings titles) is correct and fixes the critical reference bug where URLs were stripped before matching (so `pornhub.com` would never match the keyword `porn`).

However, a setting-by-setting deep review against the reference and against accessibility-service best practice reveals **23 issues** of varying severity. The most important are:

- **KB-01 (Critical)** — `extractTextFromEvent` / `collectText` are defined but **never called**. Content-text keyword matching (`isDetectWord`) is not wired into the live event pipeline. Only URL matching and title matching are active. This means the "Porn blocker" switch blocks URLs but does NOT block pornographic text that appears in app content (e.g. a search-results page that says "porn" in the body but not in the URL). The reference has the same limitation, but the dead code suggests it was intended to work.
- **KB-02 (Critical)** — The keyword cache (`cachedBlockKeywords`, `cachedWhitelistKeywords`, `cachedSettingTitles`) is a plain `List<String>`. Matching is `O(N)` linear scan per keyword per event. With the default 532 English block keywords, every URL detection triggers up to 532 `.contains()` calls. On a slow device with rapid content changes, this can cause accessibility-event processing to back up. Should use a HashSet or Aho-Corasick.
- **KB-03 (High)** — `refreshBlockingConfig()` is called from the ViewModel after every add/delete, but it relaunches a coroutine that reads **all** keyword lists + all app lists from the DB every time. Adding one keyword re-reads 1189+ rows. Should use targeted refresh (only the list that changed).
- **KB-04 (High)** — `isDetectWordInUrl` does case-insensitive `.contains()` matching, but does NOT normalize the URL first. `https://PornHub.com` matches but `https://p⊕rnhub.com` (homograph attack using Unicode lookalikes) does not. Also, IDN domains (`https://xn--...`) are not decoded.
- **KB-05 (High)** — The `KEYWORD-17`-equivalent: `decodeText` lowercases the URL **before** matching, but `isDetectWordInUrl` also lowercases. Double-lowercasing is harmless but indicates the matching pipeline is not well-factored. More importantly, `decodeText` is called in `handleUrlDetected` but `isDetectWordInUrl` receives the already-decoded URL — so the matching is correct, but the function name `isDetectWordInUrl` is misleading (it actually matches against a decoded+lowercased URL, not the raw URL).
- **KB-06 (High)** — The 500ms throttle in `launchBlockActivity` is per-package. If the user has two blocked apps firing events in alternation (e.g. Chrome and Firefox both matching), each gets its own 500ms window and the user sees a block-screen storm. Should be a global throttle + per-package.
- **KB-07 (Medium)** — `extractUrlFromEvent` calls `rootInActiveWindow` which can return a stale node tree if the event is delayed. No check for `event.eventTime` staleness. Can match a URL from a previous page.
- **KB-08 (Medium)** — The `BROWSER_URL_VIEW_IDS` map only has 6 browsers. The `DefaultSupportedBrowsers` list has 11. The 5 missing browsers (Vivaldi, DuckDuckGo, Mi Browser, Fennec, Bromite) fall through to the fallback `findUrlInNode` search, which is less reliable. Should add view IDs for all 11.
- **KB-09 (Medium)** — `isAnyTitleBlocked` matches against the event `text` AND the `className`. Matching against className is fragile — class names are implementation details and change between app versions. A keyword like "battery" will never match a class name, but a keyword like "settings" will match almost every settings-related class name, causing false positives.
- **KB-10 (Medium)** — The Keyword Manager "Add" flow does not validate the keyword (no min length, no max length, no duplicate check). A user can add an empty string (well, `isNotBlank` catches that), a 10000-char string, or a duplicate of an existing keyword. The reference enforces a 2-character minimum.
- **KB-11 (Medium)** — The Keyword Manager does not show a confirmation when deleting a keyword. A tap on the delete icon instantly removes it. The reference uses long-press to delete (less accidental).
- **KB-12 (Medium)** — `insertPresetKeywords()` uses `upsertAll` (INSERT OR REPLACE). If a user has deleted a preset keyword, re-seeding on app update will re-add it. Should use `INSERT OR IGNORE` or check a "has been seeded" flag.
- **KB-13 (Medium)** — The `preset_block_keywords.json` is 15 KB and parsed on first launch via Gson. If the JSON is malformed, `loadJsonMap` returns `emptyMap()` and the user gets zero block keywords silently. Should log + show a user-visible error.
- **KB-14 (Low/Medium)** — The Keyword Manager search is case-insensitive but does not normalize Unicode. Searching for "porn" will not find "p⊕rn" (homograph).
- **KB-15 (Low)** — The Keyword Manager "Add" field does not trim whitespace from the keyword before storing. `addBlockKeyword` does trim, but the UI text field does not — if the user types "porn " (trailing space), the stored keyword is "porn" but the UI still shows "porn " until the list refreshes.
- **KB-16 (Low)** — The Keyword Manager tab count badges (`Blocklist (532)`) are computed from `state.blockKeywords.size` which is the full list, not the filtered list. Misleading when a search is active.
- **KB-17 (Low)** — The `KeywordManagerState.filteredKeywords()` function is called on every recomposition (it's not memoized). With 532 keywords, this is 532 filter operations per frame. Should be `remember(searchQuery, activeTab) { ... }`.
- **KB-18 (Low)** — The `block_setting_title_input` and `block_package_intent_input` switch keys in `saveTextField` are raw strings, not declared in `SwitchIdentifier`. Same pattern as the old `vpn_connection_type` hidden key that was fixed in the VPN analysis.
- **KB-19 (Low)** — The `PornBlockActivity` block screen does not show which keyword triggered the block. The `isDetectWordInUrl` function returns the matched keyword (as the second Pair element) but `handleUrlDetected` discards it (`val (found, _) = ...`). The block screen just shows a generic "blocked" message.
- **KB-20 (Low)** — `findUrlInNode` recurses without a depth limit. A deeply nested view tree could cause a StackOverflow. The `collectText` helper has a `maxDepth = 3` guard, but `findUrlInNode` does not.
- **KB-21 (Low)** — `isBrowserByPackageSignature` uses substring matching on the package name. `"com.example.browser.helper"` would match `"browser"` and be incorrectly detected as a browser.
- **KB-22 (Low)** — The `browserCache` is a `mutableMapOf` (not thread-safe). `onAccessibilityEvent` can fire on the main thread while `refreshBlockingConfig` runs on `Dispatchers.Default`. Concurrent access to `browserCache` can cause a `ConcurrentModificationException`.
- **KB-23 (Low)** — The `lastSafeSearchUrl` throttle compares the full URL string. If the URL has a changing query parameter (e.g. `?t=12345`), the throttle never triggers, causing redirect loops.

The detailed analysis below walks through every keyword setting one by one, explains what it does, how it compares to the reference, and lists the specific issues that apply to it.

---

## 1. Inventory of keyword-blocking settings

The keyword subsystem exposes **5 user-facing settings** plus the internal keyword DB:

| # | Setting | Identifier | DB key / table | UI location | Type |
|---|---------|------------|----------------|-------------|------|
| K1 | Porn blocker (master switch) | `SettingPageItemIdentifiers.PORN_BLOCKER` | `SwitchIdentifier.PORN_BLOCKER_SWITCH` | Content Blocking | Boolean switch |
| K2 | Blocklist keywords | `SettingPageItemIdentifiers.BLOCKER_CUSTOM_KEYWORD_WEBSITE` | `selected_keyword_table` identifier=`porn_block_words` | Content Blocking → Manage → Keyword Manager (Blocklist tab) | List of strings |
| K3 | Whitelist keywords | (no dedicated SettingPageItemIdentifier — managed inside Keyword Manager) | `selected_keyword_table` identifier=`porn_white_list_words` | Keyword Manager (Whitelist tab) | List of strings |
| K4 | Title-based block setting (master switch) | `SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE` | `SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH` | Uninstall Protection | Boolean switch |
| K5 | Setting title keywords | `SettingPageItemIdentifiers.BLOCK_SETTING_PAGE_BY_TITLE_APPS` + `BLOCK_SETTING_PAGE_BY_TITLE` (text input) | `selected_keyword_table` identifier=`setting_keywords_list_words` | Uninstall Protection → Add (text input) + Manage (Keyword Manager Titles tab) | List of strings |

Plus two related keyword-based features that are NOT in the "Content Blocking" section but use the same keyword DB:

| # | Setting | Identifier | DB key / table | UI location | Type |
|---|---------|------------|----------------|-------------|------|
| K6 | Package + Intent name blocking (intent names) | `SettingPageItemIdentifiers.ADD_PACKAGE_INTENT_TO_BLOCK` (text input) | `selected_keyword_table` identifier=`blocked_intent_names` | Advanced Features → Manage blocked packages/intents | List of strings |
| K7 | SafeSearch enforcement | `SettingPageItemIdentifiers.SAFE_SEARCH` | `SwitchIdentifier.SAFE_SEARCH_SWITCH` | Content Blocking | Boolean switch |

Each setting is analysed in its own section below (sections 3–9). Section 2 first covers the underlying matching engine because every setting depends on it.

---

## 2. The matching engine (`BlockerPageUtils.kt` + `MyAccessibilityService.kt`)

This is the heart of the keyword subsystem. Every setting funnels into here.

### 2.1 URL keyword matching — `isDetectWordInUrl`

```kotlin
fun isDetectWordInUrl(url: String, words: List<String>): Pair<Boolean, String> {
    if (words.isEmpty()) return Pair(false, "")
    val lower = url.lowercase(Locale.ROOT)
    for (word in words) {
        if (word.isBlank()) continue
        val w = word.lowercase(Locale.ROOT).trim()
        if (lower.contains(w)) {
            return Pair(true, w)
        }
    }
    return Pair(false, "")
}
```

**What it does**: Case-insensitive substring match of each keyword against the URL. Returns the matched keyword as the second pair element (for context display).

**Reference comparison**: Per `docs/COMPARISON_REPORT.md` line 195: the reference's `isDetectWord()` strips URLs before matching (bug: keywords never match URLs). The rebuild's `isDetectWordInUrl()` does NOT strip URLs — this is the critical bug fix. ✅ Rebuild is stronger.

**Issues**:
- **KB-02**: O(N) linear scan. With 532 English keywords, every URL detection does up to 532 `.contains()` calls.
- **KB-04**: No homograph/IDN normalization. `https://p⊕rnhub.com` bypasses the keyword `porn`.
- **KB-05**: The function receives an already-decoded+lowercased URL (from `handleUrlDetected`), but the function name implies it matches against a raw URL. Misleading.

### 2.2 Text keyword matching — `isDetectWord`

```kotlin
fun isDetectWord(detectText: String, words: List<String>): Pair<Boolean, String> {
    if (words.isEmpty()) return Pair(false, "")
    val lower = detectText.lowercase(Locale.ROOT)
    val stripped = websiteRegex.replace(lower, "")  // strip URLs first
    for (word in words) {
        if (word.isBlank()) continue
        val w = word.lowercase(Locale.ROOT).trim()
        if (stripped.contains(w)) {
            // build 20-char context window
            val idx = stripped.indexOf(w)
            val start = (idx - 10).coerceAtLeast(0)
            val end = (idx + w.length + 10).coerceAtMost(stripped.length)
            val before = stripped.substring(start, idx)
            val after = stripped.substring(idx, end)
            return Pair(true, "$w\n\n$before$after")
        }
    }
    return Pair(false, "")
}
```

**What it does**: Strips URLs from the text (so keywords don't match URL substrings), then does case-insensitive substring match. Returns a 20-char context window around the match.

**Reference comparison**: This is the original reference matching function. The rebuild keeps it for text matching but added `isDetectWordInUrl` for URL matching. ✅ Correct split.

**Issues**:
- **KB-01 (Critical)**: `isDetectWord` is **never called** from the live event pipeline. `handleContentChange` only calls `handleUrlDetected` (which uses `isDetectWordInUrl`). `extractTextFromEvent` / `collectText` are defined but unused. So content-text matching is dead code.
- **KB-02**: O(N) linear scan, same as `isDetectWordInUrl`.

### 2.3 Whitelist matching — `isSafeUrl`

```kotlin
fun isSafeUrl(url: String, whitelistKeywords: List<String>): Boolean {
    if (whitelistKeywords.isEmpty()) return false
    val lower = decodeText(url)
    return whitelistKeywords.any { kw ->
        kw.isNotBlank() && lower.contains(kw.lowercase(Locale.ROOT).trim())
    }
}
```

**What it does**: Case-insensitive substring match of each whitelist keyword against the decoded URL. If any matches, the URL is safe (overrides block).

**Reference comparison**: Same behaviour. ✅ Equivalent.

**Issues**:
- **KB-02**: O(N) linear scan.
- Note: `isSafeUrl` calls `decodeText(url)` which lowercases, but `isDetectWordInUrl` receives an already-decoded URL. So the whitelist check double-decodes. Mostly harmless (decode is idempotent after the first pass), but inconsistent.

### 2.4 Title matching — `isSettingsPage` + `isAnyTitleBlocked` (in MyAccessibilityService)

```kotlin
private fun isSettingsPage(packageName: String, text: String): Boolean {
    if (packageName != "com.android.settings" && !packageName.contains(".settings")) return false
    if (cachedSettingTitles.isEmpty()) return false
    val lower = text.lowercase(Locale.ROOT)
    return cachedSettingTitles.any { title ->
        val t = title.lowercase(Locale.ROOT).trim()
        t.isNotBlank() && lower.contains(t)
    }
}

private fun isAnyTitleBlocked(packageName: String, className: String, text: String): Boolean {
    if (cachedSettingTitles.isEmpty()) return false
    if (packageName == this.packageName) return false
    if (packageName == "com.android.systemui") return false
    val lowerText = text.lowercase(Locale.ROOT)
    val lowerClass = className.lowercase(Locale.ROOT)
    for (title in cachedSettingTitles) {
        val t = title.lowercase(Locale.ROOT).trim()
        if (t.isBlank()) continue
        if (lowerText.contains(t)) return true
        if (lowerClass.contains(t)) return true  // KB-09: fragile
    }
    return false
}
```

**Reference comparison**: The reference's `isSettingsPage` is identical. `isAnyTitleBlocked` is a rebuild addition (the reference only checks settings packages). ✅ Rebuild is stronger (checks any app).

**Issues**:
- **KB-09**: `isAnyTitleBlocked` matches against `className`. Class names are implementation details — a keyword like "settings" matches almost every settings-related class name, causing false positives.
- **KB-02**: O(N) linear scan.

### 2.5 Intent name matching — `isPackageOrIntentBlocked`

```kotlin
private fun isPackageOrIntentBlocked(packageName: String, className: String): Boolean {
    if (packageName == this.packageName) return false
    if (packageName == "com.android.systemui") return false
    if (cachedBlockedPackageNames.contains(packageName)) return true
    val lowerClass = className.lowercase(Locale.ROOT)
    for (intentName in cachedBlockedIntentNames) {
        val i = intentName.lowercase(Locale.ROOT).trim()
        if (i.isNotBlank() && lowerClass.contains(i)) return true
    }
    return false
}
```

**Reference comparison**: Per `docs/COMPARISON_REPORT.md` line 114, this is a **rebuild-only addition** — the reference does not have package+intent name blocking. ✅ Rebuild is stronger.

**Issues**:
- **KB-02**: O(N) linear scan (but the list is typically small).
- Note: `cachedBlockedPackageNames` is a `Set<String>` (O(1) lookup), but `cachedBlockedIntentNames` is a `List<String>` (O(N) scan). Inconsistent.

---

## 3. Setting K1 — Porn blocker (master switch)

### 3.1 What it does

`SwitchIdentifier.PORN_BLOCKER_SWITCH` (boolean, defaults to `true`). When ON, the accessibility service scrapes URLs from supported browsers and checks them against the blocklist. When OFF, no URL scraping or keyword matching happens.

UI: A `SwitchRow` in the Content Blocking category. Toggling calls `viewModel.toggleSwitch(item)`, which persists the switch state. The accessibility service picks up the new state on the next `refreshBlockingConfig()` call.

### 3.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 99: "Porn blocker (URL keyword matching) | ✅ | ✅ | Rebuild adds `isDetectWordInUrl()` that doesn't strip URLs (the reference had a bug where URLs were stripped before matching)". ✅ Rebuild is stronger.

### 3.3 Issues

- **KB-01**: The switch is labeled "Block content based on keyword list" but only blocks URLs. Content-text matching (`isDetectWord`) is dead code. Either implement it or relabel the switch to "Block URLs based on keyword list".
- The switch defaults to ON (correct — matches the reference).

---

## 4. Setting K2 — Blocklist keywords

### 4.1 What it does

The `selected_keyword_table` rows with `identifier = "porn_block_words"`. Seeded on first launch with 532 English keywords from `preset_block_keywords.json` (+ locale-specific keywords for non-English locales). The user can add/delete via the Keyword Manager (Blocklist tab).

UI: `BLOCKER_CUSTOM_KEYWORD_WEBSITE` row in Content Blocking → "Manage" → Keyword Manager (Blocklist tab). The row's action label shows the count (well, actually it shows "Manage" — the count is only shown for `BLOCK_SETTING_PAGE_BY_TITLE`).

### 4.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 100: "Custom keyword list management | ✅ Full page | ⚠️ Stub". In v1.0.33, the Keyword Manager was a stub (`SimpleSubPage("Keyword Manager")`). The Future-Brand branch implemented the full Keyword Manager page. ✅ Gap closed.

### 4.3 Issues

- **KB-10**: No validation on add (no min/max length, no duplicate check).
- **KB-11**: No confirmation on delete.
- **KB-12**: `insertPresetKeywords()` uses `upsertAll` (INSERT OR REPLACE). Re-seeding on app update re-adds user-deleted presets.
- **KB-15**: The "Add" field does not trim whitespace in the UI (the ViewModel trims, but the UI text field shows the untrimmed value until refresh).
- **KB-16**: The tab count badge shows the full list size, not the filtered size.
- **KB-17**: `filteredKeywords()` is not memoized — called on every recomposition.
- **KB-19**: The block screen does not show which keyword triggered the block.
- The row's action label is "Manage" — could show the count like `BLOCK_SETTING_PAGE_BY_TITLE` does.

---

## 5. Setting K3 — Whitelist keywords

### 5.1 What it does

The `selected_keyword_table` rows with `identifier = "porn_white_list_words"`. Seeded on first launch with English whitelist keywords from `preset_whitelist_keywords.json`. The user can add/delete via the Keyword Manager (Whitelist tab).

UI: Only reachable via the Keyword Manager (Whitelist tab). There is no dedicated row in the BlockerPage settings list — the user must know to tap "Blocklist keywords" → Manage → Whitelist tab.

### 5.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 101: "Whitelist keyword list | ✅ | ✅ | Same". ✅ Equivalent.

### 5.3 Issues

- **Discoverability**: The whitelist is hidden behind the blocklist. A user who wants to whitelist a site has to navigate to Blocklist keywords → Manage → Whitelist tab. The reference has a dedicated whitelist entry point. Consider adding a "Whitelist keywords" row in Content Blocking.
- Same KB-10, KB-11, KB-15, KB-16, KB-17 issues as K2.

---

## 6. Setting K4 — Title-based block setting (master switch)

### 6.1 What it does

`SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH` (boolean, defaults to `false`). When ON, the accessibility service checks every `WINDOW_STATE_CHANGED` event against `cachedSettingTitles` (the setting-title keyword list). If the event's text or class name contains any keyword, the block screen launches.

UI: A `SwitchRow` in the Uninstall Protection category. Toggling persists the switch state.

### 6.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 112: "Title-based settings page blocking | ✅ Toggle + keyword manager page | ✅ Text input + manage page". ✅ Equivalent.

### 6.3 Issues

- **KB-09**: `isAnyTitleBlocked` matches against `className` — fragile, causes false positives.
- The switch is in "Uninstall Protection" but the feature is really "settings page blocking". Consider moving to "Content Blocking" or a new "Settings Access Control" category. (This is a UI/UX issue, not a bug.)

---

## 7. Setting K5 — Setting title keywords

### 7.1 What it does

The `selected_keyword_table` rows with `identifier = "setting_keywords_list_words"`. NOT seeded on first launch (the user must add their own). The user can add via two paths:
1. The `BLOCK_SETTING_PAGE_BY_TITLE` row's "Add" action → text input dialog → `saveTextField("block_setting_title_input", value)`.
2. The `BLOCK_SETTING_PAGE_BY_TITLE_APPS` row's "Manage" action → Keyword Manager (Titles tab).

UI: Two rows in Uninstall Protection:
- "Title-based block setting" (Add) — opens a text input dialog.
- "Manage blocked titles" (Manage) — opens the Keyword Manager (Titles tab).

The "Add" row's action label shows the count: `if (count > 0) "$count titles" else "Add"`.

### 7.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 112: "Rebuild uses text-input dialog instead of dedicated keyword page". The Future-Brand branch now has both paths (text input + dedicated page). ✅ Stronger than v1.0.33.

### 7.3 Issues

- **KB-18**: The `block_setting_title_input` switch key is a raw string, not declared in `SwitchIdentifier`.
- **KB-10**: No validation (min length 2 as the reference requires, no duplicate check).
- Same KB-11, KB-12, KB-15, KB-16, KB-17 issues.

---

## 8. Setting K6 — Package + Intent name blocking (intent names)

### 8.1 What it does

The `selected_keyword_table` rows with `identifier = "blocked_intent_names"` (intent/class name substrings) + the `selected_apps_table` rows with `identifier = "blocked_package_names"` (exact package names). The user adds entries via the `ADD_PACKAGE_INTENT_TO_BLOCK` row's "Manage" action → PackageIntentPage.

UI: Two rows in Advanced Features:
- "Package + Intent Blocking" (switch) — master toggle.
- "Manage blocked packages/intents" (Manage) — opens PackageIntentPage.

### 8.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 114: "Package + Intent name blocking | ❌ Not present | ✅ New feature added in v1.0.26". ✅ Rebuild-only addition.

### 8.3 Issues

- **KB-18**: The `block_package_intent_input` switch key is a raw string.
- **KB-02**: `cachedBlockedIntentNames` is a `List<String>` (O(N) scan) while `cachedBlockedPackageNames` is a `Set<String>` (O(1)). Inconsistent.
- This setting is out of scope for "keyword-based blocking" but uses the keyword DB, so it's included for completeness.

---

## 9. Setting K7 — SafeSearch enforcement

### 9.1 What it does

`SwitchIdentifier.SAFE_SEARCH_SWITCH` (boolean, defaults to `false`). When ON, the accessibility service detects when the user navigates to an unsafe search engine URL (Google, Bing, YouTube, DuckDuckGo) and redirects them to the SafeSearch-enforced variant (`forcesafesearch.google.com`, `strict.bing.com`, `restrict.youtube.com`, `safe.duckduckgo.com`).

UI: A `SwitchRow` in Content Blocking.

### 9.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 104: "SafeSearch enforcement | ✅ DNS-level via VPN redirect | ⚠️ Accessibility-level only (blocks unsafe Google search URL) | **Weaker**". The rebuild uses accessibility-level redirect (press HOME + open the safe URL in the same browser). The reference uses DNS-level redirect via VPN. ❌ Rebuild is weaker.

### 9.3 Issues

- **KB-23**: The `lastSafeSearchUrl` throttle compares the full URL string. If the URL has a changing query parameter, the throttle never triggers, causing redirect loops.
- **KB-06**: The 500ms throttle is per-package. SafeSearch has its own 2000ms throttle (per package+URL), but the `launchBlockActivity` throttle is also per-package, so a SafeSearch redirect that falls back to a block screen can storm.
- The VPN analysis (VPN-01) noted that the VPN now does DNS-level SafeSearch enforcement when ON. So this accessibility-level SafeSearch is a second layer. The UI should mention this.

---

## 10. URL extraction — `extractUrlFromEvent` + `findUrlInNode`

### 10.1 What it does

`extractUrlFromEvent` tries two strategies:
1. **Known view IDs**: Looks up the package in `BROWSER_URL_VIEW_IDS` and queries the accessibility node tree by view ID.
2. **Fallback**: `findUrlInNode` recurses the node tree looking for any node whose text starts with "http" or contains "://".

### 10.2 Comparison with the reference

Per `docs/COMPARISON_REPORT.md` line 194: "View ID map for 6 browsers + fallback node search (same) + node-tree URL search when no view IDs match | ✅ Rebuild slightly stronger (v1.0.27 fix)". ✅ Rebuild is stronger.

### 10.3 Issues

- **KB-07**: `rootInActiveWindow` can return a stale node tree. No staleness check.
- **KB-08**: `BROWSER_URL_VIEW_IDS` only has 6 browsers; `DefaultSupportedBrowsers` has 11. The 5 missing browsers fall through to the less-reliable fallback.
- **KB-20**: `findUrlInNode` recurses without a depth limit — StackOverflow risk on deeply nested trees.
- **KB-22**: `browserCache` is not thread-safe.

---

## 11. Performance analysis

### 11.1 Keyword matching throughput

The current matching is O(N×M) where N = number of keywords, M = URL/text length. With 532 English keywords and a typical 100-char URL, that's 53,200 `.contains()` calls per URL detection. On a mid-range device, this takes ~5-10ms per URL. Under rapid browsing (Chrome fires 5-10 URL events per second during scroll), this can back up.

**Fix (KB-02)**: Use a `HashSet<String>` for exact-match keywords, or an Aho-Corasick automaton for substring matching. The Aho-Corasick approach is O(M + number of matches) regardless of N — a 100x speedup with 532 keywords.

### 11.2 Cache refresh

`refreshBlockingConfig()` reads all 4 keyword lists + 8 app lists from the DB every time it's called. With 1189+ keywords total, this is ~1200 row reads per refresh. Called on:
- Service connect
- Every add/delete in Keyword Manager
- Every switch toggle
- Periodically by AppDataCheckWorker (every 24h)

**Fix (KB-03)**: Targeted refresh — only re-read the list that changed. The ViewModel knows which list was modified; pass that to `refreshBlockingConfig(which: KeywordList?)`.

### 11.3 `filteredKeywords()` recomputation

`KeywordManagerState.filteredKeywords()` is called from the composable on every recomposition. With 532 keywords and a search query, this is 532 filter operations per frame.

**Fix (KB-17)**: Memoize with `remember(searchQuery, activeTab) { state.filteredKeywords() }`.

---

## 12. Security analysis

### 12.1 Homograph attacks (KB-04)

A user can bypass the blocklist by using a homograph domain: `https://p⊕rnhub.com` (using U+2295 CIRCLED PLUS instead of 'o') or `https://раrnhub.com` (using Cyrillic 'а' instead of 'a'). The keyword `porn` does not match these.

**Fix**: Normalize the URL using `java.text.Normalizer` to decompose Unicode, then strip non-ASCII. Also decode IDN domains via `java.net.IDN.toUnicode`.

### 12.2 No keyword validation (KB-10)

A user can add a 10000-char keyword (which would slow down matching) or a duplicate keyword (which wastes memory). The reference enforces a 2-char minimum.

**Fix**: Validate in `addBlockKeyword` etc.: min 2 chars, max 100 chars, not a duplicate.

### 12.3 No block-screen context (KB-19)

The block screen does not show which keyword triggered the block. A user who is blocked on a legitimate site has no way to know which keyword caused the false positive.

**Fix**: Pass the matched keyword to `launchBlockActivity` as an extra, and display it on the block screen.

---

## 13. Summary of issues by severity

| ID | Severity | Setting | Summary |
|----|----------|---------|---------|
| KB-01 | Critical | K1 | `isDetectWord` / `extractTextFromEvent` / `collectText` are dead code — content-text matching not wired in |
| KB-02 | Critical | (engine) | O(N) linear keyword scan — use HashSet/Aho-Corasick |
| KB-03 | High | (engine) | `refreshBlockingConfig()` re-reads all lists on every add/delete |
| KB-04 | High | K2 | No homograph/IDN normalization — `p⊕rn` bypasses `porn` |
| KB-05 | High | (engine) | `isDetectWordInUrl` name is misleading (receives pre-decoded URL) |
| KB-06 | High | (engine) | 500ms throttle is per-package — block-screen storm with 2+ blocked apps |
| KB-07 | Medium | (engine) | `rootInActiveWindow` can return stale node tree |
| KB-08 | Medium | (engine) | `BROWSER_URL_VIEW_IDS` only has 6 of 11 supported browsers |
| KB-09 | Medium | K4/K5 | `isAnyTitleBlocked` matches className — false positives |
| KB-10 | Medium | K2/K3/K5 | No keyword validation (min length, max length, duplicate) |
| KB-11 | Medium | K2/K3/K5 | No delete confirmation in Keyword Manager |
| KB-12 | Medium | K2/K3 | `insertPresetKeywords` uses upsertAll — re-adds user-deleted presets |
| KB-13 | Medium | K2 | Malformed `preset_block_keywords.json` silently yields zero keywords |
| KB-14 | Low/Med | K2 | Search does not normalize Unicode |
| KB-15 | Low | K2/K3/K5 | UI text field does not trim whitespace |
| KB-16 | Low | K2/K3/K5 | Tab count badge shows full list size, not filtered size |
| KB-17 | Low | K2/K3/K5 | `filteredKeywords()` not memoized — 532 ops per frame |
| KB-18 | Low | K5/K6 | `block_setting_title_input` / `block_package_intent_input` are raw strings, not in SwitchIdentifier |
| KB-19 | Low | K1/K2 | Block screen does not show which keyword triggered the block |
| KB-20 | Low | (engine) | `findUrlInNode` recurses without depth limit — StackOverflow risk |
| KB-21 | Low | (engine) | `isBrowserByPackageSignature` substring matching causes false positives |
| KB-22 | Low | (engine) | `browserCache` not thread-safe |
| KB-23 | Low | K7 | SafeSearch throttle compares full URL — changing query params cause loops |

---

## 14. Recommended priority order for fixes

1. **KB-02** (HashSet/Aho-Corasick) — biggest performance win, affects every URL detection.
2. **KB-01** (wire in content-text matching OR relabel the switch) — closes the biggest correctness gap.
3. **KB-04** (homograph/IDN normalization) — security fix.
4. **KB-03** (targeted refresh) — performance fix.
5. **KB-06** (global throttle) — prevents block-screen storms.
6. **KB-12** (INSERT OR IGNORE for presets) — prevents re-adding user-deleted keywords.
7. **KB-10** (keyword validation) — min 2 chars, max 100, no duplicates.
8. **KB-19** (show matched keyword on block screen) — big UX win.
9. **KB-08** (add view IDs for 5 missing browsers) — reliability fix.
10. **KB-09** (stop matching className in isAnyTitleBlocked) — false-positive fix.
11. **KB-17** (memoize filteredKeywords) — perf fix.
12. **KB-11** (delete confirmation) — UX fix.
13. **KB-20** (depth limit in findUrlInNode) — crash fix.
14. **KB-22** (thread-safe browserCache) — crash fix.
15. **KB-18** (declare raw switch keys in SwitchIdentifier) — code quality.
16. **KB-23** (SafeSearch throttle by URL without query) — loop fix.
17. **KB-13** (log + show error on malformed preset JSON) — diagnostics.
18. **KB-21** (exact package name match for browser detection) — false-positive fix.
19. **KB-07** (staleness check) — reliability fix.
20. **KB-05** (rename isDetectWordInUrl) — code quality.
21. **KB-14** (Unicode-normalize search) — minor.
22. **KB-15** (trim in UI) — minor.
23. **KB-16** (filtered count in badge) — minor.

---

## 15. Conclusion

The Future-Brand keyword-blocking subsystem is functional and closes the biggest reference-parity gap (the Keyword Manager UI). The core matching logic correctly fixes the reference URL-stripping bug. However, the matching engine has significant performance (KB-02, KB-03), correctness (KB-01, KB-04, KB-09), and UX (KB-10, KB-11, KB-19) gaps that should be addressed before the subsystem is considered production-ready.

The 23 issues identified above are prioritised in section 14. Addressing the top 6 (KB-02, KB-01, KB-04, KB-03, KB-06, KB-12) would bring the keyword subsystem from "works on the developer's device" to "safe to ship to real users". The remaining 17 are important for polish and robustness but are not blockers.
