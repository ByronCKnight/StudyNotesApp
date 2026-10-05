package com.studynotes.app.library.ui

import com.studynotes.app.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LibraryFormattingTest {

    private fun size(bytes: Long) = formatFileSize(bytes, Locale.US)

    @Test
    fun bytesBelowOneKilobyteAreShownExactly() {
        assertEquals("0 B", size(0))
        assertEquals("1 B", size(1))
        assertEquals("999 B", size(999))
    }

    @Test
    fun negativeSizesClampToZero() {
        assertEquals("0 B", size(-5))
    }

    @Test
    fun kilobytesUseDecimalUnits() {
        assertEquals("1.0 KB", size(1_000))
        assertEquals("9.9 KB", size(9_940))
        assertEquals("18 KB", size(18_000))
        assertEquals("18 KB", size(18_432))
        assertEquals("999 KB", size(999_499))
    }

    @Test
    fun megabytesKeepOneDecimalBelowTen() {
        assertEquals("2.4 MB", size(2_400_000))
        assertEquals("2.4 MB", size(2_449_999))
        assertEquals("12 MB", size(12_300_000))
        assertEquals("152 MB", size(152_300_000))
    }

    @Test
    fun roundingUpMovesToTheNextUnit() {
        assertEquals("1.0 MB", size(999_999))
        assertEquals("10 KB", size(9_960))
        assertEquals("1.0 GB", size(999_600_000))
    }

    @Test
    fun hugeSizesStayInTheLargestUnit() {
        assertEquals("1.5 TB", size(1_500_000_000_000))
        assertEquals("5000 TB", size(5_000_000_000_000_000))
    }

    @Test
    fun decimalSeparatorFollowsLocale() {
        assertEquals("2,4 MB", formatFileSize(2_400_000, Locale.GERMANY))
    }

    @Test
    fun typeLabels() {
        assertEquals("PDF", DocType.PDF.label)
        assertEquals("Word", DocType.DOCX.label)
    }

    @Test
    fun metaLineIsTypeAndSize() {
        assertEquals("PDF · 2.4 MB", metaLine(DocType.PDF, 2_400_000, Locale.US))
        assertEquals("Word · 18 KB", metaLine(DocType.DOCX, 18_000, Locale.US))
    }

    @Test
    fun marksLabelsForTheBadge() {
        assertEquals("1 mark", marksLabel(1))
        assertEquals("12 marks", marksLabel(12))
        assertEquals("12", marksBadgeText(12))
        assertEquals("999", marksBadgeText(999))
        assertEquals("999+", marksBadgeText(1_000))
    }

    @Test
    fun deleteConfirmationSaysTheFileIsGoneForGood() {
        assertEquals(
            "“Lecture 3.pdf” will be permanently deleted from Documents/StudyNotes on this device, " +
                "along with its highlights and notes. This can't be undone.",
            deleteConfirmationText("Lecture 3.pdf", "Documents/StudyNotes", isPublic = true),
        )
        assertEquals(
            "“Lecture 3.pdf” will be permanently deleted from this device, along with its highlights " +
                "and notes. This can't be undone.",
            deleteConfirmationText("Lecture 3.pdf", "App storage (private)", isPublic = false),
        )
    }

    @Test
    fun documentsLeftBehindMessageCountsThem() {
        assertEquals(
            "1 document couldn't be moved to Documents/StudyNotes, so it's hidden for now (nothing was " +
                "deleted). Free up some space, then reopen StudyNotes.",
            documentsLeftBehindMessage(1, "Documents/StudyNotes"),
        )
        assertEquals(
            "3 documents couldn't be moved to Documents/StudyNotes, so they're hidden for now (nothing " +
                "was deleted). Free up some space, then reopen StudyNotes.",
            documentsLeftBehindMessage(3, "Documents/StudyNotes"),
        )
    }

    @Test
    fun importSummaryForOneDocumentNamesIt() {
        assertEquals("Imported “Lecture 3”", importSummary(listOf("Lecture 3"), emptyList()))
    }

    @Test
    fun importSummaryForSeveralDocumentsCountsThem() {
        assertEquals("Imported 2 documents", importSummary(listOf("A", "B"), emptyList()))
    }

    @Test
    fun importSummaryListsFailures() {
        assertEquals(
            "Imported 2 documents. Couldn't import photo.jpg (not a PDF or Word file)",
            importSummary(listOf("A", "B"), listOf("photo.jpg (not a PDF or Word file)")),
        )
        assertEquals(
            "Couldn't import a.zip (unsupported), b.png (unsupported) and 2 more",
            importSummary(emptyList(), listOf("a.zip (unsupported)", "b.png (unsupported)", "c", "d")),
        )
    }

    @Test
    fun importSummaryWhenNothingHappened() {
        assertEquals("Nothing was imported", importSummary(emptyList(), emptyList()))
    }

    @Test
    fun importSummarySaysWhatWasAlreadyThere() {
        assertEquals("“Notes” is already in your library", importSummary(emptyList(), emptyList(), listOf("Notes")))
        assertEquals(
            "Imported “A”. 2 documents were already in your library",
            importSummary(listOf("A"), emptyList(), listOf("B", "C")),
        )
    }
}
