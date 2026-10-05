package com.studynotes.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportNamingTest {

    @Test
    fun appendsAnnotatedSuffix() {
        assertEquals("Biology Lecture Notes (annotated).pdf", ExportNaming.annotatedPdfName("Biology Lecture Notes"))
    }

    @Test
    fun keepsUnicodeAndPunctuationThatIsSafe() {
        assertEquals("Café – Week 3 (draft) #2 (annotated).pdf", ExportNaming.annotatedPdfName("Café – Week 3 (draft) #2"))
    }

    @Test
    fun replacesForbiddenAndControlCharacters() {
        assertEquals("a_b_c_d_e_f_g_h_i_j", ExportNaming.safeTitle("a/b\\c:d*e?f\"g<h>i|j"))
        assertEquals("x_y", ExportNaming.safeTitle("x\u0000y"))
    }

    @Test
    fun normalisesWhitespace() {
        assertEquals("Chapter 1 Notes", ExportNaming.safeTitle("  Chapter\t1 \n\n Notes  "))
    }

    @Test
    fun stripsLeadingDotsAndTrailingDotsAndSpaces() {
        assertEquals("hidden", ExportNaming.safeTitle("..hidden"))
        assertEquals("Summary", ExportNaming.safeTitle("Summary. . "))
    }

    @Test
    fun emptyOrUnusableTitleFallsBack() {
        assertEquals(ExportNaming.FALLBACK_TITLE, ExportNaming.safeTitle(""))
        assertEquals(ExportNaming.FALLBACK_TITLE, ExportNaming.safeTitle("   "))
        assertEquals(ExportNaming.FALLBACK_TITLE, ExportNaming.safeTitle("..."))
    }

    @Test
    fun longAsciiTitleIsCutToMaxChars() {
        val title = ExportNaming.safeTitle("a".repeat(500))
        assertEquals(ExportNaming.MAX_TITLE_CHARS, title.length)
    }

    @Test
    fun multiByteTitleIsCutToMaxBytes() {
        val title = ExportNaming.safeTitle("日本語".repeat(100))
        val bytes = title.toByteArray(Charsets.UTF_8).size
        assertTrue("$bytes bytes", bytes <= ExportNaming.MAX_TITLE_BYTES)
        assertEquals(ExportNaming.MAX_TITLE_BYTES / 3, title.length)
    }

    @Test
    fun neverSplitsASurrogatePair() {
        val title = ExportNaming.safeTitle("x" + "📚".repeat(100))
        assertFalse(title.last().isHighSurrogate())
        assertTrue(title.toByteArray(Charsets.UTF_8).size <= ExportNaming.MAX_TITLE_BYTES)
        assertEquals(title, String(title.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun fullNameFitsTheFileNameLimitWithRoomForPartSuffix() {
        val name = ExportNaming.annotatedPdfName("Ünïcödé ".repeat(80))
        assertTrue((name + ".part").toByteArray(Charsets.UTF_8).size <= 255)
        assertTrue(name.endsWith(ExportNaming.SUFFIX))
    }

    @Test
    fun documentKeyIsAShortSha1OfTheFullFileName() {
        // SHA-1("abc") = a9993e364706816aba3e25717850c26c9cd0d89d
        assertEquals("a9993e364706816a", ExportNaming.documentKey("abc"))
        assertEquals(ExportNaming.DOCUMENT_KEY_CHARS, ExportNaming.documentKey("Lecture 3.pdf").length)
        assertTrue(ExportNaming.documentKey("日本語.docx").all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun documentKeyTellsSameTitledDocumentsApart() {
        assertNotEquals(ExportNaming.documentKey("Lecture 3.pdf"), ExportNaming.documentKey("Lecture 3.docx"))
        assertNotEquals(ExportNaming.documentKey("A:B.pdf"), ExportNaming.documentKey("A_B.pdf"))
        assertEquals(ExportNaming.documentKey("Notes.pdf"), ExportNaming.documentKey("Notes.pdf"))
    }
}
