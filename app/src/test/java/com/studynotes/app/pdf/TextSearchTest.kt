package com.studynotes.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextSearchTest {

    private fun found(text: String, query: String): List<String> =
        TextSearch.find(text, query).map { text.substring(it) }

    @Test
    fun findsEveryOccurrence_ignoringCase_inOrder() {
        val text = "The cell membrane. Cells have a membrane; CELL walls differ."
        assertEquals(listOf("cell", "Cell", "CELL"), found(text, "cell"))
        assertEquals(listOf(4..7, 19..22, 42..45), TextSearch.find(text, "CELL"))
    }

    @Test
    fun occurrencesDoNotOverlap() {
        assertEquals(listOf("aa", "aa"), found("aaaa", "aa"))
        assertEquals(listOf("aa"), found("aaa", "aa"))
    }

    @Test
    fun blankQuery_andEmptyText_findNothing() {
        assertTrue(TextSearch.find("some text", "").isEmpty())
        assertTrue(TextSearch.find("some text", "   \n").isEmpty())
        assertTrue(TextSearch.find("", "text").isEmpty())
        assertTrue(TextSearch.find("short", "much longer query").isEmpty())
    }

    @Test
    fun whitespaceInTheQuery_matchesAnyWhitespaceInTheText() {
        val text = "Plants make\nsugar   from  light.\r\nSugar\tfrom light."
        assertEquals(listOf("make\nsugar"), found(text, "make sugar"))
        assertEquals(listOf("sugar   from  light", "Sugar\tfrom light"), found(text, "  sugar   from\nlight "))
    }

    @Test
    fun aQueryWithoutSpaces_doesNotMatchAcrossSpaces() {
        assertTrue(TextSearch.find("make sugar", "makesugar").isEmpty())
    }

    @Test
    fun ligatures_matchTheirLetters() {
        val text = "The ﬁrst eﬀect of oﬃcial ﬂags"
        assertEquals(listOf("ﬁrst"), found(text, "first"))
        assertEquals(listOf("eﬀect"), found(text, "effect"))
        assertEquals(listOf("oﬃcial"), found(text, "official"))
        assertEquals(listOf("ﬂags"), found(text, "flags"))
        // A hit ending inside a ligature still ends at that character ("fi" is also inside "oﬃcial").
        assertEquals(listOf(4..4, 19..19), TextSearch.find(text, "fi"))
        // Typing the ligature itself works too.
        assertEquals(listOf("ﬁrst"), found(text, "ﬁrst"))
    }

    @Test
    fun rangesIndexTheOriginalText_whenWhitespaceWasCollapsed() {
        val text = "   a  b   c"
        assertEquals(listOf(3..6), TextSearch.find(text, "a b"))
        assertEquals(listOf(6..10), TextSearch.find(text, "b c"))
    }

    @Test
    fun normalizeQuery_trimsAndCollapsesWhitespace() {
        assertEquals("cell membrane", TextSearch.normalizeQuery("  cell \n\t membrane  "))
        assertEquals("", TextSearch.normalizeQuery(" \n "))
        assertEquals("fine", TextSearch.normalizeQuery("ﬁne"))
        // Case is kept: matching ignores it anyway, and the user sees what they typed.
        assertEquals("Cell", TextSearch.normalizeQuery("Cell"))
    }

    @Test
    fun largeText_withManyHits() {
        val text = buildString { repeat(5_000) { append("word ") } }
        assertEquals(5_000, TextSearch.find(text, "word").size)
        // Non-overlapping: "word word" pairs up the words.
        assertEquals(2_500, TextSearch.find(text, "word word").size)
    }
}
