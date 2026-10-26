package com.studynotes.app.editor

import com.studynotes.app.pdf.SearchHit

/** What the find bar shows and what the canvas marks on the pages. */
data class SearchState(
    /** The find bar is open. */
    val active: Boolean = false,
    /** The text as typed. */
    val query: String = "",
    /** Every occurrence found so far, in document order. */
    val hits: List<SearchHit> = emptyList(),
    /** Index into [hits] of the occurrence being shown; -1 while there is none. */
    val current: Int = -1,
    /** Pages are still being searched (more hits may come). */
    val searching: Boolean = false,
    /** Bumped whenever [currentHit] should be brought into view: the first hit found, Next, Previous. */
    val showSerial: Int = 0,
) {
    val currentHit: SearchHit? get() = hits.getOrNull(current)
}

/**
 * Collects the hits of one search. The pages are searched from the one the reader is on to the
 * end and then from the start ([pageOrder]), so the nearest hit ahead is found, and shown, first;
 * [hits] is nevertheless always in document order. Pure logic, unit-tested.
 */
internal class SearchResults(pageCount: Int, startPage: Int) {

    private val start = if (pageCount <= 0) 0 else startPage.coerceIn(0, pageCount - 1)

    /** Hits on pages before the start page (added after [after]'s, since those pages are searched last). */
    private val before = ArrayList<SearchHit>()

    /** Hits on the start page and after it. */
    private val after = ArrayList<SearchHit>()

    /** The pages to search, in order: the start page to the last, then the first up to the start page. */
    val pageOrder: IntArray = IntArray(pageCount.coerceAtLeast(0)) { (start + it) % pageCount }

    /** Records the hits of [page] ([ranges] stop exclusive, in order). Pages must come in [pageOrder]. */
    fun add(page: Int, ranges: List<IntRange>) {
        val into = if (page >= start) after else before
        for (r in ranges) into += SearchHit(page, r.first, r.last + 1)
    }

    /** Every hit so far in document order. A new list each time, so it can be compared by identity. */
    val hits: List<SearchHit> get() = ArrayList<SearchHit>(before.size + after.size).apply { addAll(before); addAll(after) }

    /** Index in [hits] of the hit to show first: the first on or after the start page, else the first in the document. */
    val firstHitIndex: Int get() = if (after.isNotEmpty()) before.size else 0
}
