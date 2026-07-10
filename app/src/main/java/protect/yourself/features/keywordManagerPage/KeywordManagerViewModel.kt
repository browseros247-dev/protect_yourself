package protect.yourself.features.keywordManagerPage

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import protect.yourself.database.core.AppDatabase
import protect.yourself.database.selectedKeywords.SelectedKeywordIdentifier
import protect.yourself.database.selectedKeywords.SelectedKeywordItemModel
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

    private val _state = MutableStateFlow(KeywordManagerState())
    val state: StateFlow<KeywordManagerState> = _state.asStateFlow()

    init {
        observeAllLists()
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
     * Add a new keyword to the blocklist.
     */
    fun addBlockKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val item = SelectedKeywordItemModel(
                    key = "block_${System.currentTimeMillis()}_${trimmed.hashCode()}",
                    keyword = trimmed,
                    identifier = SelectedKeywordIdentifier.PORN_BLOCK_WORDS.value,
                    isSelected = true
                )
                db.selectedKeywordDao().upsert(item)
                refreshAccessibility()
                Timber.i("Added block keyword: $trimmed")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to add block keyword: $trimmed")
            }
        }
    }

    /**
     * Add a new keyword to the whitelist.
     */
    fun addWhitelistKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val item = SelectedKeywordItemModel(
                    key = "white_${System.currentTimeMillis()}_${trimmed.hashCode()}",
                    keyword = trimmed,
                    identifier = SelectedKeywordIdentifier.PORN_WHITE_LIST_WORDS.value,
                    isSelected = true
                )
                db.selectedKeywordDao().upsert(item)
                refreshAccessibility()
                Timber.i("Added whitelist keyword: $trimmed")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to add whitelist keyword: $trimmed")
            }
        }
    }

    /**
     * Add a new setting title keyword.
     */
    fun addSettingTitleKeyword(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            try {
                val item = SelectedKeywordItemModel(
                    key = "setting_${System.currentTimeMillis()}_${trimmed.hashCode()}",
                    keyword = trimmed,
                    identifier = SelectedKeywordIdentifier.SETTING_KEYWORDS_LIST_WORDS.value,
                    isSelected = true
                )
                db.selectedKeywordDao().upsert(item)
                refreshAccessibility()
                Timber.i("Added setting title keyword: $trimmed")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to add setting title keyword: $trimmed")
            }
        }
    }

    /**
     * Delete a keyword by key.
     */
    fun deleteKeyword(key: String) {
        viewModelScope.launch {
            try {
                db.selectedKeywordDao().deleteByKey(key)
                refreshAccessibility()
                Timber.i("Deleted keyword: $key")
            } catch (t: Throwable) {
                Timber.e(t, "Failed to delete keyword: $key")
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
        try {
            protect.yourself.features.blockerPage.service.MyAccessibilityService.instance
                ?.refreshBlockingConfig()
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
    val searchQuery: String = ""
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
