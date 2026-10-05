package com.studynotes.app.pdf

/**
 * A page's text, for growing a text selection to whole words: a highlight that starts or ends a
 * little inside a word still covers all of it ("eactions … oxyge" becomes "reactions … oxygen").
 *
 * PDFium reports a point-based selection without character indices but accepts indices (stop
 * exclusive) for a new one, so [ModernBackend] finds where the selected text sits in the page text
 * ([occurrences]; it confirms the right one by its bounds), then asks for [wholeWords] of it.
 * Pure logic, so it is unit-testable.
 */
internal class PageText(private val text: String) {

    /** [text] without whitespace, and for each of its characters the index in [text]. */
    private val compact: String
    private val rawIndex: IntArray

    init {
        val sb = StringBuilder(text.length)
        val raw = IntArray(text.length)
        for (i in text.indices) {
            if (!text[i].isWhitespace()) {
                raw[sb.length] = i
                sb.append(text[i])
            }
        }
        compact = sb.toString()
        rawIndex = raw.copyOf(sb.length)
    }

    /**
     * Every place [selected] could be in the page text, ignoring whitespace (selections report line
     * breaks differently), as start to exclusive end; null when there are more than [limit].
     */
    fun occurrences(selected: String, limit: Int): List<Pair<Int, Int>>? {
        val wanted = selected.filterNot(Char::isWhitespace)
        if (wanted.isEmpty()) return emptyList()
        val found = ArrayList<Pair<Int, Int>>()
        var at = compact.indexOf(wanted)
        while (at >= 0) {
            if (found.size == limit) return null
            found += rawIndex[at] to rawIndex[at + wanted.length - 1] + 1
            at = compact.indexOf(wanted, at + 1)
        }
        return found
    }

    /**
     * [from] until [until] grown over the whitespace next to it: [before] / [after] say whether the
     * selection reported whitespace on that side (a stroke that ends in the gap after a word).
     */
    fun withAdjacentWhitespace(from: Int, until: Int, before: Boolean, after: Boolean): Pair<Int, Int> {
        var start = from
        if (before) while (start > 0 && text[start - 1].isWhitespace()) start--
        var end = until
        if (after) while (end < text.length && text[end].isWhitespace()) end++
        return start to end
    }

    /** [from] until [until] (exclusive) grown to word boundaries; null when it already ends on them. */
    fun wholeWords(from: Int, until: Int): Pair<Int, Int>? {
        if (from < 0 || until > text.length || from >= until) return null
        var start = from
        while (start > 0 && isWordChar(text[start - 1]) && isWordChar(text[start])) start--
        var end = until
        while (end < text.length && isWordChar(text[end - 1]) && isWordChar(text[end])) end++
        return if (start == from && end == until) null else start to end
    }

    private fun isWordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '\'' || c == '’'
}
