package protect.yourself.features.blockerPage.utils

import java.util.ArrayDeque

/**
 * KeywordMatcher — Aho-Corasick automaton for fast multi-keyword substring matching.
 *
 * KB-02 fix: the original implementation used O(N×M) linear scan (N keywords ×
 * M text length) for every URL/text detection. With 532 default English block
 * keywords and a typical 100-char URL, that was ~53,200 `.contains()` calls per
 * URL detection. Under rapid browsing (5-10 URL events per second) this backed
 * up the accessibility event pipeline.
 *
 * The Aho-Corasick automaton precompiles the keyword set into a trie with
 * failure links, enabling O(M + number_of_matches) matching regardless of N.
 * With 532 keywords this is a ~100x speedup.
 *
 * Thread-safety: the automaton is built once from a keyword list and is
 * immutable thereafter. To change the keyword set, build a new KeywordMatcher
 * and replace the reference. Matching is safe to call from any thread.
 *
 * Usage:
 * ```
 * val matcher = KeywordMatcher(listOf("porn", "xxx", "adult"))
 * val result = matcher.findFirst("https://pornhub.com")
 * // result = Match(keyword="porn", start=8, end=12)
 * ```
 */
class KeywordMatcher(
    keywords: Collection<String>,
    private val caseInsensitive: Boolean = true
) {
    data class Match(val keyword: String, val start: Int, val end: Int)

    private val root: Node = Node()

    init {
        // Build the trie.
        for (rawKeyword in keywords) {
            val keyword = normalize(rawKeyword)
            if (keyword.isEmpty()) continue
            var node = root
            for (ch in keyword) {
                node = node.children.getOrPut(ch) { Node() }
            }
            node.output = keyword
        }
        // Build failure links via BFS.
        val queue = ArrayDeque<Node>()
        for (child in root.children.values) {
            child.failure = root
            queue.add(child)
        }
        while (queue.isNotEmpty()) {
            val node = queue.poll() ?: continue
            for ((ch, child) in node.children) {
                queue.add(child)
                var f = node.failure
                while (f != null && ch !in f.children) {
                    f = f.failure
                }
                child.failure = f?.children?.get(ch) ?: root
                if (child.failure === child) child.failure = root
                // Merge output from failure node so we don't miss shorter
                // keywords that end at this position.
                if (child.output == null) {
                    child.output = child.failure?.output
                }
            }
        }
    }

    /**
     * Returns the first matching keyword in [text], or null if none match.
     * Use this when you only need to know whether ANY keyword matches (e.g.
     * block decisions).
     */
    fun findFirst(text: String): Match? {
        val normalized = if (caseInsensitive) text.lowercase() else text
        var node = root
        for ((i, ch) in normalized.withIndex()) {
            while (node !== root && ch !in node.children) {
                node = node.failure ?: root
            }
            node = node.children[ch] ?: run {
                // No transition — stay at root (failure already applied above)
                if (node.failure != null && ch in node.failure!!.children) {
                    node.failure!!.children[ch]!!
                } else {
                    root
                }
            }
            node.output?.let { kw ->
                val start = i - kw.length + 1
                return Match(kw, start, i + 1)
            }
        }
        return null
    }

    /**
     * Returns ALL matching keywords in [text]. Use this when you need the
     * complete list (e.g. for diagnostics or highlighting).
     */
    fun findAll(text: String): List<Match> {
        val normalized = if (caseInsensitive) text.lowercase() else text
        val results = mutableListOf<Match>()
        var node = root
        for ((i, ch) in normalized.withIndex()) {
            while (node !== root && ch !in node.children) {
                node = node.failure ?: root
            }
            node = node.children[ch] ?: run {
                if (node.failure != null && ch in node.failure!!.children) {
                    node.failure!!.children[ch]!!
                } else {
                    root
                }
            }
            // Walk the failure chain to collect all outputs at this position.
            var outNode = node
            while (outNode !== root) {
                outNode.output?.let { kw ->
                    val start = i - kw.length + 1
                    results.add(Match(kw, start, i + 1))
                }
                outNode = outNode.failure ?: break
            }
        }
        return results
    }

    private fun normalize(s: String): String {
        val trimmed = s.trim()
        return if (caseInsensitive) trimmed.lowercase() else trimmed
    }

    private class Node {
        val children: MutableMap<Char, Node> = HashMap()
        var failure: Node? = null
        var output: String? = null
    }
}
