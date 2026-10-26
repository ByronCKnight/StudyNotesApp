package com.studynotes.app.editor

import com.studynotes.app.pdf.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentSearchTest {

    @Test
    fun pageOrder_startsAtTheCurrentPage_andWrapsRound() {
        assertEquals(listOf(2, 3, 4, 0, 1), SearchResults(5, 2).pageOrder.toList())
        assertEquals(listOf(0, 1, 2), SearchResults(3, 0).pageOrder.toList())
        assertEquals(listOf(2, 0, 1), SearchResults(3, 2).pageOrder.toList())
        // Out-of-range start pages are clamped; no pages, no order.
        assertEquals(listOf(2, 0, 1), SearchResults(3, 7).pageOrder.toList())
        assertEquals(listOf(0, 1, 2), SearchResults(3, -1).pageOrder.toList())
        assertTrue(SearchResults(0, 0).pageOrder.isEmpty())
    }

    @Test
    fun hitsStayInDocumentOrder_whilePagesArriveInSearchOrder() {
        val results = SearchResults(5, 2)
        results.add(3, listOf(0..3))
        results.add(4, listOf(10..12, 20..22))
        results.add(0, listOf(5..5))
        results.add(1, listOf(7..9))
        assertEquals(
            listOf(
                SearchHit(0, 5, 6),
                SearchHit(1, 7, 10),
                SearchHit(3, 0, 4),
                SearchHit(4, 10, 13),
                SearchHit(4, 20, 23),
            ),
            results.hits,
        )
    }

    @Test
    fun firstHit_isTheFirstOnOrAfterTheCurrentPage_elseTheFirstInTheDocument() {
        val results = SearchResults(5, 2)
        assertEquals(0, results.firstHitIndex)
        results.add(0, listOf(1..1)) // only possible after wrapping: nothing on pages 2..4
        assertEquals(0, results.firstHitIndex)

        val ahead = SearchResults(5, 2)
        ahead.add(3, listOf(0..3))
        assertEquals(0, ahead.firstHitIndex)
        ahead.add(0, listOf(1..1, 3..3))
        // The hit on page 3 is still the one to show; it is now third in document order.
        assertEquals(2, ahead.firstHitIndex)
        assertEquals(SearchHit(3, 0, 4), ahead.hits[ahead.firstHitIndex])
    }

    @Test
    fun hitsIsANewListEachTime() {
        val results = SearchResults(2, 0)
        results.add(0, listOf(0..0))
        val a = results.hits
        val b = results.hits
        assertNotSame(a, b)
        assertEquals(a, b)
    }

    @Test
    fun searchState_currentHit() {
        val hits = listOf(SearchHit(0, 0, 2), SearchHit(1, 4, 6))
        assertNull(SearchState().currentHit)
        assertNull(SearchState(hits = hits, current = -1).currentHit)
        assertNull(SearchState(hits = hits, current = 2).currentHit)
        assertEquals(SearchHit(1, 4, 6), SearchState(hits = hits, current = 1).currentHit)
    }
}
