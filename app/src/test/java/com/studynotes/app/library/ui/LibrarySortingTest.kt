package com.studynotes.app.library.ui

import com.studynotes.app.data.SortOrder
import com.studynotes.app.model.DocType
import com.studynotes.app.model.LibraryDocument
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.Collator
import java.util.Locale

class LibrarySortingTest {

    private fun doc(
        name: String,
        lastModified: Long = 0L,
        lastOpened: Long? = null,
        annotationCount: Int = 0,
    ) = LibraryDocument(
        file = File("/library", name),
        type = DocType.fromFileName(name) ?: DocType.PDF,
        sizeBytes = 1_000,
        lastModified = lastModified,
        lastOpened = lastOpened,
        annotationCount = annotationCount,
    )

    private fun List<LibraryDocument>.names() = map { it.fileName }

    private fun sorted(docs: List<LibraryDocument>, order: SortOrder, query: String = "") =
        sortAndFilter(docs, order, query, Locale.US).names()

    @Test
    fun nameOrderIsNaturalAndCaseInsensitive() {
        val docs = listOf(
            doc("Lecture 10.pdf"),
            doc("lecture 2.pdf"),
            doc("Biology.docx"),
            doc("apple notes.pdf"),
            doc("Lecture 1.pdf"),
        )
        assertEquals(
            listOf("apple notes.pdf", "Biology.docx", "Lecture 1.pdf", "lecture 2.pdf", "Lecture 10.pdf"),
            sorted(docs, SortOrder.NAME),
        )
    }

    @Test
    fun nameOrderBreaksTitleTiesByFileName() {
        val docs = listOf(doc("Notes.pdf"), doc("Notes.docx"))
        assertEquals(listOf("Notes.docx", "Notes.pdf"), sorted(docs, SortOrder.NAME))
    }

    @Test
    fun recentPutsLastOpenedFirstAndCountsNewImports() {
        val docs = listOf(
            doc("Old untouched.pdf", lastModified = 100),
            doc("Opened yesterday.pdf", lastModified = 50, lastOpened = 1_000),
            doc("Just imported.pdf", lastModified = 2_000),
            doc("Opened today.pdf", lastModified = 10, lastOpened = 3_000),
        )
        assertEquals(
            listOf("Opened today.pdf", "Just imported.pdf", "Opened yesterday.pdf", "Old untouched.pdf"),
            sorted(docs, SortOrder.RECENT),
        )
    }

    @Test
    fun recentTiesFallBackToName() {
        val docs = listOf(doc("b.pdf", lastModified = 5), doc("a.pdf", lastModified = 5))
        assertEquals(listOf("a.pdf", "b.pdf"), sorted(docs, SortOrder.RECENT))
    }

    @Test
    fun dateAddedIgnoresOpeningAndPutsNewestFileFirst() {
        val docs = listOf(
            doc("First.pdf", lastModified = 1, lastOpened = 9_999),
            doc("Third.pdf", lastModified = 3),
            doc("Second.pdf", lastModified = 2),
        )
        assertEquals(listOf("Third.pdf", "Second.pdf", "First.pdf"), sorted(docs, SortOrder.DATE_ADDED))
    }

    @Test
    fun blankQueryKeepsEverything() {
        val docs = listOf(doc("a.pdf"), doc("b.pdf"))
        assertEquals(2, sortAndFilter(docs, SortOrder.NAME, "   ", Locale.US).size)
    }

    @Test
    fun queryMatchesTitleIgnoringCaseAndAccents() {
        val docs = listOf(doc("Résumé tips.pdf"), doc("Cell Biology.docx"), doc("Biology Lecture 3.pdf"))
        assertEquals(listOf("Résumé tips.pdf"), sorted(docs, SortOrder.NAME, "resume"))
        assertEquals(listOf("Biology Lecture 3.pdf", "Cell Biology.docx"), sorted(docs, SortOrder.NAME, "BIOLOGY"))
    }

    @Test
    fun everyQueryWordMustMatchInAnyOrder() {
        val docs = listOf(doc("Cell Biology Study Guide.docx"), doc("Biology Lecture Notes.pdf"))
        assertEquals(listOf("Cell Biology Study Guide.docx"), sorted(docs, SortOrder.NAME, "guide  cell"))
        assertEquals(emptyList<String>(), sorted(docs, SortOrder.NAME, "guide lecture"))
    }

    @Test
    fun queryDoesNotMatchTheExtension() {
        val docs = listOf(doc("Minimal.docx"))
        assertTrue(sortAndFilter(docs, SortOrder.NAME, "docx", Locale.US).isEmpty())
    }

    @Test
    fun recencyIsTheLaterOfOpenedAndModified() {
        assertEquals(500L, recency(doc("a.pdf", lastModified = 500, lastOpened = 100)))
        assertEquals(900L, recency(doc("a.pdf", lastModified = 500, lastOpened = 900)))
        assertEquals(500L, recency(doc("a.pdf", lastModified = 500, lastOpened = null)))
    }

    @Test
    fun searchTokensAreNormalised() {
        assertEquals(listOf("cafe", "notes"), searchTokens("  Café   NOTES "))
        assertEquals(emptyList<String>(), searchTokens(" \t "))
    }

    @Test
    fun naturalComparatorHandlesNumbersAndMixedRuns() {
        val comparator = NaturalOrderComparator(Collator.getInstance(Locale.US).apply { strength = Collator.SECONDARY })
        val input = listOf("x10", "x9", "x09", "x", "9x", "x1y2", "x1y10", "x100000000000000000000")
        assertEquals(
            listOf("9x", "x", "x1y2", "x1y10", "x9", "x09", "x10", "x100000000000000000000"),
            input.sortedWith(comparator),
        )
    }
}
