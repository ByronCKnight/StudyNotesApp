package com.studynotes.app.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFileNamesTest {

    @Test
    fun sanitizeStripsCharactersFileSystemsReject() {
        assertEquals("Lecture CellsPart 1", LibraryFileNames.sanitizeTitle("Lecture: Cells/Part 1?"))
        assertEquals("Quote", LibraryFileNames.sanitizeTitle("\"Quote\""))
        assertEquals("ab", LibraryFileNames.sanitizeTitle("a<>|*\\b"))
    }

    @Test
    fun sanitizeTurnsControlCharactersIntoSingleSpaces() {
        assertEquals("Line1 Line2 End", LibraryFileNames.sanitizeTitle("Line1\nLine2\t\t End"))
        assertEquals("a b", LibraryFileNames.sanitizeTitle("a\u0000\u007Fb"))
    }

    @Test
    fun sanitizeTrimsDotsAndSpacesSoFilesNeverBecomeHidden() {
        assertEquals("hidden name", LibraryFileNames.sanitizeTitle("  ..hidden name.. "))
        assertEquals("v1.2 notes", LibraryFileNames.sanitizeTitle(".v1.2 notes."))
    }

    @Test
    fun sanitizeReturnsNullWhenNothingIsLeft() {
        assertNull(LibraryFileNames.sanitizeTitle(""))
        assertNull(LibraryFileNames.sanitizeTitle("   "))
        assertNull(LibraryFileNames.sanitizeTitle("..."))
        assertNull(LibraryFileNames.sanitizeTitle("?*:/"))
        assertEquals("Document", LibraryFileNames.sanitizeTitleOrDefault("?*:/"))
        assertEquals("Document", LibraryFileNames.sanitizeTitleOrDefault(null))
    }

    @Test
    fun sanitizeLimitsLengthInChars() {
        assertEquals(120, LibraryFileNames.sanitizeTitle("a".repeat(300))!!.length)
        // The cut lands right after a space, which is then trimmed.
        assertEquals("a".repeat(119), LibraryFileNames.sanitizeTitle("a".repeat(119) + " bcd"))
    }

    @Test
    fun sanitizeLimitsLengthInUtf8Bytes() {
        val cjk = LibraryFileNames.sanitizeTitle("日".repeat(100))!!
        assertEquals(66, cjk.length) // 66 x 3 bytes = 198 <= 200
        assertTrue(cjk.toByteArray(Charsets.UTF_8).size <= LibraryFileNames.MAX_TITLE_BYTES)
    }

    @Test
    fun sanitizeNeverSplitsSurrogatePairsAndDropsLoneHalves() {
        val emoji = LibraryFileNames.sanitizeTitle("📚".repeat(70))!!
        assertEquals("📚".repeat(50), emoji) // 50 x 4 bytes = 200
        assertEquals("ab", LibraryFileNames.sanitizeTitle("a\uD800b"))
        assertEquals("ab", LibraryFileNames.sanitizeTitle("a\uDC00b"))
    }

    @Test
    fun importTitleDropsDocumentExtensionsOnly() {
        assertEquals("Biology", LibraryFileNames.titleForImport("Biology.pdf"))
        assertEquals("Report", LibraryFileNames.titleForImport("Report.DOCX"))
        assertEquals("Old", LibraryFileNames.titleForImport("Old.doc"))
        assertEquals("Notes v2.1", LibraryFileNames.titleForImport("Notes v2.1"))
        assertEquals("Lecture 3", LibraryFileNames.titleForImport("Lecture 3"))
        assertEquals("Document", LibraryFileNames.titleForImport(".pdf"))
        assertEquals("Document", LibraryFileNames.titleForImport(null))
    }

    @Test
    fun uniqueNameReturnsTheNameWhenFree() {
        assertEquals("Lecture.pdf", LibraryFileNames.uniqueFileName("Lecture", "pdf") { false })
    }

    @Test
    fun uniqueNameCountsUpCaseInsensitively() {
        val taken = setOf("lecture.pdf", "lecture (2).pdf")
        val isTaken = { name: String -> LibraryFileNames.nameKey(name) in taken }
        assertEquals("Lecture (3).pdf", LibraryFileNames.uniqueFileName("Lecture", "pdf", isTaken))
        assertEquals("LECTURE (3).pdf", LibraryFileNames.uniqueFileName("LECTURE", "pdf", isTaken))
        assertEquals("Lecture.docx", LibraryFileNames.uniqueFileName("Lecture", "docx", isTaken))
    }

    @Test
    fun uniqueNameContinuesAnExistingCounter() {
        val taken = setOf("lecture (2).pdf")
        val isTaken = { name: String -> LibraryFileNames.nameKey(name) in taken }
        assertEquals("Lecture (3).pdf", LibraryFileNames.uniqueFileName("Lecture (2)", "pdf", isTaken))
        assertEquals(
            "Exercise (2).pdf",
            LibraryFileNames.uniqueFileName("Exercise (1)", "pdf") { it == "Exercise (1).pdf" },
        )
    }

    @Test
    fun documentNamesExcludeHiddenTempAndOtherFiles() {
        assertTrue(LibraryFileNames.isLibraryDocumentName("a.pdf"))
        assertTrue(LibraryFileNames.isLibraryDocumentName("A.PDF"))
        assertTrue(LibraryFileNames.isLibraryDocumentName("Study guide.docx"))
        assertFalse(LibraryFileNames.isLibraryDocumentName(".hidden.pdf"))
        assertFalse(LibraryFileNames.isLibraryDocumentName(".Lecture.pdf.part"))
        assertFalse(LibraryFileNames.isLibraryDocumentName("old.doc"))
        assertFalse(LibraryFileNames.isLibraryDocumentName("notes.txt"))
        assertFalse(LibraryFileNames.isLibraryDocumentName(".studynotes"))
    }

    @Test
    fun partFileIsHiddenAndNamedAfterTheSource() {
        assertEquals(".Lecture.pdf.part", LibraryFileNames.partFileName("Lecture.pdf"))
        assertEquals(".Document.part", LibraryFileNames.partFileName(null))
        assertEquals(".ab.part", LibraryFileNames.partFileName("a/b"))
    }
}
