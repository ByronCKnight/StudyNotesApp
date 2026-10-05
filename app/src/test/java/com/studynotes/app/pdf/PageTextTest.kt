package com.studynotes.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PageTextTest {
    private val raw = "In the light-dependent reactions, water is split and oxygen is released.\r\nNext line, and oxygen again"
    private val page = PageText(raw)

    @Test
    fun findsTheSelectionIgnoringWhitespace() {
        val from = raw.indexOf("leased")
        val until = raw.indexOf("Next") + 2
        assertEquals(listOf(from to until), page.occurrences("leased.\nNe", limit = 10))
    }

    @Test
    fun listsEveryPlaceAndGivesUpBeyondTheLimit() {
        val first = raw.indexOf("oxygen")
        val second = raw.lastIndexOf("oxygen")
        assertEquals(listOf(first to first + 6, second to second + 6), page.occurrences("oxygen", limit = 10))
        assertNull(page.occurrences("e", limit = 3))
        assertEquals(emptyList<Pair<Int, Int>>(), page.occurrences("not on the page", limit = 10))
    }

    @Test
    fun growsToWholeWords() {
        val from = raw.indexOf("reactions") + 1 // "eactions"
        val until = raw.indexOf("oxygen") + 5 // "... oxyge"
        assertEquals(raw.indexOf("reactions") to raw.indexOf("oxygen") + 6, page.wholeWords(from, until))
    }

    @Test
    fun wholeWordsAndHyphenatedPartsStayAsTheyAre() {
        val r = raw.indexOf("reactions")
        assertNull(page.wholeWords(r, r + "reactions".length))
        // A hyphen is a word boundary: "dependent" alone stays "dependent".
        val d = raw.indexOf("dependent")
        assertNull(page.wholeWords(d, d + "dependent".length))
    }

    @Test
    fun growsAcrossALineBreakSelection() {
        val from = raw.indexOf("released") + 2
        val until = raw.indexOf("Next") + 2
        assertEquals(raw.indexOf("released") to raw.indexOf("Next") + 4, page.wholeWords(from, until))
    }

    @Test
    fun rejectsBadRanges() {
        assertNull(page.wholeWords(-1, 3))
        assertNull(page.wholeWords(5, 5))
        assertNull(page.wholeWords(3, raw.length + 1))
    }
}
