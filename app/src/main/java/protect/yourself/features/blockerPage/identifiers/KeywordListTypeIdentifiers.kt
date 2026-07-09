package protect.yourself.features.blockerPage.identifiers

/**
 * Keyword list filter types.
 *
 * Ported from original KeywordListTypeIdentifiers.kt.
 *
 *  - ALL: show both keywords and website URLs
 *  - KEYWORDS: show only non-URL keywords
 *  - WEBSITE: show only URL-formatted entries
 */
enum class KeywordListTypeIdentifiers(val value: Long) {
    ALL(0),
    KEYWORDS(1),
    WEBSITE(2);

    companion object {
        fun fromValue(value: Long): KeywordListTypeIdentifiers? =
            values().firstOrNull { it.value == value }

        fun fromString(s: String?): KeywordListTypeIdentifiers {
            if (s.isNullOrBlank()) return ALL
            return fromValue(s.toLongOrNull() ?: 0L) ?: ALL
        }
    }
}
