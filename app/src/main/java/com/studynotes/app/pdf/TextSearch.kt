package com.studynotes.app.pdf

/** One occurrence of a search query: characters [start] until [end] (stop exclusive) of the text of page [page]. */
data class SearchHit(val page: Int, val start: Int, val end: Int)

/**
 * Finds a query in the text of a page. Pure logic, unit-tested; the text comes from the PDF engine
 * ([PdfDocumentHandle.pageText]) and the ranges index into it, like the words of a [PageTextLayout].
 */
internal object TextSearch {

    /**
     * [query] trimmed, with every run of whitespace collapsed to one space and ligatures spelled
     * out (see [find]). Empty for a blank query. Two queries with the same result find the same hits.
     */
    fun normalizeQuery(query: String): String = fold(query).text

    /**
     * Character ranges (stop exclusive) of every occurrence of [query] in [text], in order and not
     * overlapping. Case is ignored; a run of whitespace in either matches any run of whitespace in
     * the other, so a phrase broken across a line (or page column) is still found; and the ligatures
     * PDFs often use (ﬁ, ﬂ, ﬀ, ﬃ, ﬄ, ﬅ, ﬆ) match their letters. A blank query matches nothing.
     */
    fun find(text: String, query: String): List<IntRange> {
        val q = normalizeQuery(query)
        if (q.isEmpty() || text.isEmpty()) return emptyList()
        val folded = fold(text)
        val out = ArrayList<IntRange>()
        var from = 0
        while (from <= folded.text.length - q.length) {
            val at = folded.text.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            val start = folded.origin[at]
            val end = folded.origin[at + q.length - 1] + 1
            out += start until end
            from = at + q.length
        }
        return out
    }

    /** [text] with whitespace runs collapsed and ligatures expanded, and the original index of each of its characters. */
    private class Folded(val text: String, val origin: IntArray)

    private fun fold(text: String): Folded {
        val sb = StringBuilder(text.length)
        var origin = IntArray(text.length + 8)
        // Leading whitespace is dropped; a run elsewhere becomes one space (emitted before the next character).
        var pendingSpace = false
        var spaceAt = 0
        fun put(index: Int) {
            if (sb.length == origin.size) origin = origin.copyOf(origin.size * 2)
            origin[sb.length] = index
        }
        for (i in text.indices) {
            val c = text[i]
            if (c.isWhitespace()) {
                if (sb.isNotEmpty() && !pendingSpace) {
                    pendingSpace = true
                    spaceAt = i
                }
                continue
            }
            if (pendingSpace) {
                put(spaceAt)
                sb.append(' ')
                pendingSpace = false
            }
            val letters = LIGATURES[c]
            if (letters == null) {
                put(i)
                sb.append(c)
            } else {
                for (l in letters) {
                    put(i)
                    sb.append(l)
                }
            }
        }
        return Folded(sb.toString(), origin)
    }

    /** Typographic ligatures that PDF text often contains, and the letters a user types for them. */
    private val LIGATURES = mapOf(
        'ﬀ' to "ff",
        'ﬁ' to "fi",
        'ﬂ' to "fl",
        'ﬃ' to "ffi",
        'ﬄ' to "ffl",
        'ﬅ' to "st",
        'ﬆ' to "st",
    )
}
