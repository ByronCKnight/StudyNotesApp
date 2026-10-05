package com.studynotes.app.library.ui

import com.studynotes.app.data.SortOrder
import com.studynotes.app.model.LibraryDocument
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val WHITESPACE = Regex("\\s+")

/**
 * The documents the library grid shows: those whose title contains every word of [query]
 * (case- and accent-insensitive), ordered by [order].
 *
 * - [SortOrder.RECENT]: most recently opened or added first (a never-opened import counts from the
 *   moment its file was written, so new imports appear at the top).
 * - [SortOrder.NAME]: natural, locale-aware title order ("Lecture 2" before "Lecture 10").
 * - [SortOrder.DATE_ADDED]: newest file first (file modification time, which is when the file was
 *   copied into the library).
 * Ties fall back to name order so the grid never reshuffles between refreshes.
 */
fun sortAndFilter(
    documents: List<LibraryDocument>,
    order: SortOrder,
    query: String,
    locale: Locale = Locale.getDefault(),
): List<LibraryDocument> {
    val tokens = searchTokens(query)
    val matching = if (tokens.isEmpty()) {
        documents
    } else {
        documents.filter { doc ->
            val title = normalizeForSearch(doc.title)
            tokens.all { it in title }
        }
    }
    return matching.sortedWith(documentComparator(order, locale))
}

/** Comparator implementing the grid order for [order]; see [sortAndFilter]. */
fun documentComparator(order: SortOrder, locale: Locale = Locale.getDefault()): Comparator<LibraryDocument> {
    val titles = NaturalOrderComparator(
        Collator.getInstance(locale).apply { strength = Collator.SECONDARY },
    )
    val byName = compareBy(titles) { doc: LibraryDocument -> doc.title }.thenBy { it.fileName }
    return when (order) {
        SortOrder.RECENT -> compareByDescending(::recency).then(byName)
        SortOrder.NAME -> byName
        SortOrder.DATE_ADDED -> compareByDescending { doc: LibraryDocument -> doc.lastModified }.then(byName)
    }
}

/** When the user last touched [doc]: opened it, or (if later) added/replaced the file. */
fun recency(doc: LibraryDocument): Long = maxOf(doc.lastOpened ?: Long.MIN_VALUE, doc.lastModified)

/** Lower-cases [text] and strips accents so "résumé" matches a search for "Resume". */
fun normalizeForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)

/** The words of a search query, normalised with [normalizeForSearch]. Empty for a blank query. */
fun searchTokens(query: String): List<String> =
    normalizeForSearch(query).split(WHITESPACE).filter { it.isNotEmpty() }

/**
 * Orders strings the way people expect file names to sort: runs of ASCII digits compare by numeric
 * value ("Chapter 9" < "Chapter 10"), everything else through [textComparator] (typically a
 * [Collator], so case and accents follow the user's locale). A digit sorts before a letter.
 */
class NaturalOrderComparator(private val textComparator: Comparator<in String>) : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val aDigit = a[i].isAsciiDigit()
            val bDigit = b[j].isAsciiDigit()
            if (aDigit != bDigit) return if (aDigit) -1 else 1
            val endA = runEnd(a, i, aDigit)
            val endB = runEnd(b, j, bDigit)
            val chunkA = a.substring(i, endA)
            val chunkB = b.substring(j, endB)
            val result = if (aDigit) compareNumbers(chunkA, chunkB) else textComparator.compare(chunkA, chunkB)
            if (result != 0) return result
            i = endA
            j = endB
        }
        return when {
            i < a.length -> 1
            j < b.length -> -1
            else -> 0
        }
    }

    private fun runEnd(s: String, start: Int, digits: Boolean): Int {
        var end = start
        while (end < s.length && s[end].isAsciiDigit() == digits) end++
        return end
    }

    /** Compares two digit strings by value without overflowing on very long numbers. */
    private fun compareNumbers(a: String, b: String): Int {
        val trimmedA = a.trimStart('0')
        val trimmedB = b.trimStart('0')
        if (trimmedA.length != trimmedB.length) return trimmedA.length.compareTo(trimmedB.length)
        val byValue = trimmedA.compareTo(trimmedB)
        // Same value: fewer leading zeros first, so "7" < "07" deterministically.
        return if (byValue != 0) byValue else a.length.compareTo(b.length)
    }

    private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
}
