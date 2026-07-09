package protect.yourself.database.selectedKeywords

enum class SelectedKeywordIdentifier(val value: String) {
    PORN_BLOCK_WORDS("porn_block_words"),
    PORN_WHITE_LIST_WORDS("porn_white_list_words"),
    SETTING_KEYWORDS_LIST_WORDS("setting_keywords_list_words");

    companion object {
        fun fromValue(value: String): SelectedKeywordIdentifier? =
            values().firstOrNull { it.value == value }
    }
}
