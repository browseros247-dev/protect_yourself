package protect.yourself.features.keywordManagerPage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
import protect.yourself.database.switchStatus.SwitchIdentifier
import protect.yourself.database.switchStatus.SwitchStatusValues
import protect.yourself.features.blockerPage.utils.BlockingValidator
import protect.yourself.features.blockerPage.utils.ValidationResult
import timber.log.Timber

/**
 * KeywordManagerViewModel — state management for the Keyword Manager page.
 *
 * Manages three keyword lists simultaneously:
 *  - Blocklist keywords (PORN_BLOCK_WORDS) — trigger block on URL match
 *  - Whitelist keywords (PORN_WHITE_LIST_WORDS) — override block
 *  - Setting title keywords (SETTING_KEYWORDS_LIST_WORDS) — block settings pages by title
 *
 * All three lists are displayed in separate sections. User can:
 *  - Add new keyword (auto-classified: if contains "://" or starts with "www." → whitelist; else blocklist)
 *  - Delete any keyword by key
 *  - Switch active tab to filter
 */
class KeywordManagerViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val db = AppDatabase.getInstance(application)
    private val switchValues = SwitchStatusValues(db.switchStatusDao())

    private val _state = MutableStateFlow(KeywordManagerState())
    val state: StateFlow<KeywordManagerState> = _state.asStateFlow()

    /**
     * One-shot UI events (toast feedback for add/delete/validation errors).
     * Consumed by the UI via [collect]. Each event is emitted at most once per
     * emission — the buffer drops old events if the UI is slow to consume.
     */
    private val _events = MutableSharedFlow<KeywordManagerEvent>(
        extraBufferCapacity = 8
    )
    val events: SharedFlow<KeywordManagerEvent> = _events.asSharedFlow()

    init {
        observeAllLists()
        observeSettingTitleSwitch()
    }

    private fun observeAllLists() {
        // Observe blocklist keywords
        viewModelScope.launch {
            try {
                db.selectedKeywordDao()
                    .observeByIdentifier(SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value)
                    .collect { keywords ->
                        _state.update { it.copy(blockKeywords = keywords) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe block keywords")
            }
        }

        // Observe whitelist keywords
        viewModelScope.launch {
            try {
                db.selectedKeywordDao()
                    .observeByIdentifier(SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value)
                    .collect { keywords ->
                        _state.update { it.copy(whitelistKeywords = keywords) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe whitelist keywords")
            }
        }

        // Observe setting title keywords
        viewModelScope.launch {
            try {
                db.selectedKeywordDao()
                    .observeByIdentifier(SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS.value)
                    .collect { keywords ->
                        _state.update { it.copy(settingTitleKeywords = keywords) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe setting title keywords")
            }
        }
    }

    /**
     * Observe the BLOCK_SETTING_PAGE_BY_TITLE_SWITCH so the UI can render the
     * master toggle for setting-title blocking live. Previously this switch
     * was hidden in the DB and only flipped to ON by the legacy
     * `saveTextField` flow — leaving the user with no way to turn it back off
     * without using the legacy flow.
     */
    private fun observeSettingTitleSwitch() {
        viewModelScope.launch {
            try {
                db.switchStatusDao()
                    .observe(SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH)
                    .collect { item ->
                        val isOn = item?.asBoolean() ?: false
                        _state.update { it.copy(isSettingTitleSwitchOn = isOn) }
                    }
            } catch (t: Throwable) {
                Timber.e(t, "Failed to observe setting-title switch")
            }
        }
    }

    /**
     * Add a new keyword to the blocklist.
     * Emits a [KeywordManagerEvent] with the validation outcome so the UI can
     * show inline feedback (success toast or error message).
     */
    fun addBlockKeyword(keyword: String) {
        val result = BlockingValidator.validateKeyword(
            keyword,
            _state.value.blockKeywords.map { it.keyword }
        )
        if (result is ValidationResult.Valid) {
            val normalized = result.normalized
            viewModelScope.launch {
                try {
                    val item = SelectedKeywordItemModel(
                        key = "block_${System.currentTimeMillis()}_${normalized.hashCode()}",
                        keyword = normalized,
                        identifier = SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value,
                        isSelected = true
                    )
                    db.selectedKeywordDao().upsert(item)
                    refreshAccessibility()
                    Timber.i("Added block keyword: $normalized")
                    _events.emit(KeywordManagerEvent.Added(normalized, "blocklist"))
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to add block keyword: $normalized")
                    _events.emit(
                        KeywordManagerEvent.Error(
                            "Failed to add: ${t.message ?: "unknown error"}"
                        )
                    )
                }
            }
        } else {
            Timber.w("Rejected block keyword '$keyword': $result")
            viewModelScope.launch {
                _events.emit(KeywordManagerEvent.ValidationFailed(result))
            }
        }
    }

    /**
     * Add a new keyword to the whitelist.
     * Emits a [KeywordManagerEvent] with the validation outcome so the UI can
     * show inline feedback.
     */
    fun addWhitelistKeyword(keyword: String) {
        val result = BlockingValidator.validateKeyword(
            keyword,
            _state.value.whitelistKeywords.map { it.keyword }
        )
        if (result is ValidationResult.Valid) {
            val normalized = result.normalized
            viewModelScope.launch {
                try {
                    val item = SelectedKeywordItemModel(
                        key = "white_${System.currentTimeMillis()}_${normalized.hashCode()}",
                        keyword = normalized,
                        identifier = SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value,
                        isSelected = true
                    )
                    db.selectedKeywordDao().upsert(item)
                    refreshAccessibility()
                    Timber.i("Added whitelist keyword: $normalized")
                    _events.emit(KeywordManagerEvent.Added(normalized, "whitelist"))
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to add whitelist keyword: $normalized")
                    _events.emit(
                        KeywordManagerEvent.Error(
                            "Failed to add: ${t.message ?: "unknown error"}"
                        )
                    )
                }
            }
        } else {
            Timber.w("Rejected whitelist keyword '$keyword': $result")
            viewModelScope.launch {
                _events.emit(KeywordManagerEvent.ValidationFailed(result))
            }
        }
    }

    /**
     * Add a new setting title keyword.
     *
     * Side-effect: auto-enables the BLOCK_SETTING_PAGE_BY_TITLE_SWITCH on the
     * first add (mirrors the legacy `saveTextField` flow). The user can
     * subsequently toggle the switch OFF via [setSettingTitleSwitchEnabled].
     */
    fun addSettingTitleKeyword(keyword: String) {
        val result = BlockingValidator.validateKeyword(
            keyword,
            _state.value.settingTitleKeywords.map { it.keyword }
        )
        if (result is ValidationResult.Valid) {
            val normalized = result.normalized
            viewModelScope.launch {
                try {
                    val item = SelectedKeywordItemModel(
                        key = "setting_${System.currentTimeMillis()}_${normalized.hashCode()}",
                        keyword = normalized,
                        identifier = SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS.value,
                        isSelected = true
                    )
                    db.selectedKeywordDao().upsert(item)
                    // Auto-enable the master switch so the user does not have
                    // to find it separately. Mirrors the legacy flow.
                    if (!switchValues.isBlockSettingPageByTitleSwitchOn()) {
                        switchValues.storeSwitchStatus(
                            SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH,
                            true
                        )
                        Timber.i("Auto-enabled BLOCK_SETTING_PAGE_BY_TITLE_SWITCH")
                    }
                    refreshAccessibility()
                    Timber.i("Added setting title keyword: $normalized")
                    _events.emit(KeywordManagerEvent.Added(normalized, "setting titles"))
                } catch (t: Throwable) {
                    Timber.e(t, "Failed to add setting title keyword: $normalized")
                    _events.emit(
                        KeywordManagerEvent.Error(
                            "Failed to add: ${t.message ?: "unknown error"}"
                        )
                    )
                }
            }
        } else {
            Timber.w("Rejected setting title keyword '$keyword': $result")
            viewModelScope.launch {
                _events.emit(KeywordManagerEvent.ValidationFailed(result))
            }
        }
    }

    /**
     * Toggle the BLOCK_SETTING_PAGE_BY_TITLE_SWITCH. Exposed so the unified
     * blocking page can render a master switch on the "Setting Titles" tab.
     */
    fun setSettingTitleSwitchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                switchValues.storeSwitchStatus(
                    SwitchIdentifier.BLOCK_SETTING_PAGE_BY_TITLE_SWITCH,
                    enabled
                )
                _state.update { it.copy(isSettingTitleSwitchOn = enabled) }
                refreshAccessibility()
                Timber.i("Setting-title switch set to $enabled")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to set setting-title switch to $enabled")
                _events.emit(
                    KeywordManagerEvent.Error(
                        "Failed to toggle switch: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    /**
     * Delete a keyword by key.
     * Emits a [KeywordManagerEvent.Deleted] event for UI feedback.
     */
    fun deleteKeyword(key: String) {
        viewModelScope.launch {
            try {
                // Look up the keyword before deleting so we can include it in
                // the success event (used for the "Removed 'X'" toast).
                val existing = listOf(
                    *_state.value.blockKeywords.toTypedArray(),
                    *_state.value.whitelistKeywords.toTypedArray(),
                    *_state.value.settingTitleKeywords.toTypedArray()
                ).firstOrNull { it.key == key }

                db.selectedKeywordDao().deleteByKey(key)
                refreshAccessibility()
                Timber.i("Deleted keyword: $key")
                _events.emit(
                    KeywordManagerEvent.Deleted(existing?.keyword ?: "entry")
                )
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete keyword: $key")
                _events.emit(
                    KeywordManagerEvent.Error(
                        "Failed to delete: ${t.message ?: "unknown error"}"
                    )
                )
            }
        }
    }

    /**
     * Filter the currently active list by search query.
     */
    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    /**
     * Switch the active tab.
     */
    fun setActiveTab(tab: KeywordTab) {
        _state.update { it.copy(activeTab = tab, searchQuery = "") }
    }

    private fun refreshAccessibility() {
        // KB-03 fix: use targeted refresh (only the changed list) instead of
        // full refreshBlockingConfig (which re-reads ALL 1189+ rows).
        // The active tab tells us which list was modified.
        try {
            val service = protect.yourself.features.blockerPage.service.MyAccessibilityService.instance
            val identifier = when (_state.value.activeTab) {
                KeywordTab.BLOCKLIST -> SelectedKeywordIdentifier.PORN_BLOCK_WORDS
                KeywordTab.WHITELIST -> SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS
                KeywordTab.SETTING_TITLES -> SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS
            }
            service?.refreshKeywordList(identifier)
        } catch (_: Throwable) {}
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return KeywordManagerViewModel(application) as T
                }
            }
    }
}

enum class KeywordTab(val title: String) {
    BLOCKLIST("Blocklist"),
    WHITELIST("Whitelist"),
    SETTING_TITLES("Setting Titles")
}

data class KeywordManagerState(
    val blockKeywords: List<SelectedKeywordItemModel> = emptyList(),
    val whitelistKeywords: List<SelectedKeywordItemModel> = emptyList(),
    val settingTitleKeywords: List<SelectedKeywordItemModel> = emptyList(),
    val activeTab: KeywordTab = KeywordTab.BLOCKLIST,
    val searchQuery: String = "",
    /**
     * Live state of the BLOCK_SETTING_PAGE_BY_TITLE_SWITCH.
     * Used by the UnifiedBlockingPage to render a master toggle on the
     * "Setting Titles" tab. When OFF, setting-title keywords are stored but
     * NOT enforced by the accessibility service.
     */
    val isSettingTitleSwitchOn: Boolean = false
) {
    /** Returns the keywords for the active tab, filtered by search query. */
    fun filteredKeywords(): List<SelectedKeywordItemModel> {
        val source = when (activeTab) {
            KeywordTab.BLOCKLIST -> blockKeywords
            KeywordTab.WHITELIST -> whitelistKeywords
            KeywordTab.SETTING_TITLES -> settingTitleKeywords
        }
        return if (searchQuery.isBlank()) source
        else source.filter { it.keyword.contains(searchQuery, ignoreCase = true) }
    }

    /** Total count across all tabs. */
    fun totalCount(): Int = blockKeywords.size + whitelistKeywords.size + settingTitleKeywords.size
}

/**
 * One-shot UI events emitted by [KeywordManagerViewModel].
 *
 * The UnifiedBlockingPage collects these via [KeywordManagerViewModel.events]
 * and shows appropriate feedback (Toast / inline error) to the user.
 */
sealed class KeywordManagerEvent {
    /** Entry was successfully added to the named list. */
    data class Added(val entry: String, val listName: String) : KeywordManagerEvent()

    /** Entry was successfully deleted. */
    data class Deleted(val entry: String) : KeywordManagerEvent()

    /** Input failed validation. [result] carries the specific failure. */
    data class ValidationFailed(val result: ValidationResult) : KeywordManagerEvent()

    /** An unexpected error occurred (DB write failure, etc). */
    data class Error(val message: String) : KeywordManagerEvent()
}
