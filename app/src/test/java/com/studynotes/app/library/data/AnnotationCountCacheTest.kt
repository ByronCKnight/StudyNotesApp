package com.studynotes.app.library.data

import com.studynotes.app.model.AnnotationFile
import com.studynotes.app.model.AnnotationJson
import com.studynotes.app.model.DocType
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.SourceInfo
import com.studynotes.app.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AnnotationCountCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var sidecar: File
    private lateinit var backup: File
    private val cache = AnnotationCountCache()

    @Before
    fun setUp() {
        sidecar = File(tmp.root, "Lecture.pdf.json")
        backup = File(tmp.root, "Lecture.pdf.json.bak")
    }

    private fun json(strokesOnPage0: Int, notesOnPage3: Int): String {
        val strokes = List(strokesOnPage0) {
            Stroke("s$it", InkTool.HIGHLIGHTER, 0xFFFFE94D.toInt(), 14f, floatArrayOf(1f, 2f, 3f, 4f))
        }
        val notes = List(notesOnPage3) { Note("n$it", 10f, 20f, "note $it", 0xFFFFF3A6.toInt(), 1L, 2L) }
        val pages = buildMap {
            if (strokes.isNotEmpty()) put(0, PageAnnotations(strokes = strokes))
            if (notes.isNotEmpty()) put(3, PageAnnotations(notes = notes))
        }
        val file = AnnotationFile(SourceInfo("Lecture.pdf", DocType.PDF, 1L, 1L), 0L, DocumentAnnotations(pages))
        return AnnotationJson.encode(file)
    }

    @Test
    fun countsStrokesAndNotes() {
        sidecar.writeText(json(strokesOnPage0 = 2, notesOnPage3 = 1))
        assertEquals(3, cache.count(sidecar, backup))
    }

    @Test
    fun missingSidecarCountsZero() {
        assertEquals(0, cache.count(sidecar, backup))
    }

    @Test
    fun changedSidecarIsDecodedAgain() {
        sidecar.writeText(json(1, 0))
        assertEquals(1, cache.count(sidecar, backup))
        sidecar.writeText(json(4, 2))
        assertEquals(6, cache.count(sidecar, backup))
    }

    @Test
    fun unchangedSidecarIsServedFromTheCache() {
        val text = json(2, 2)
        sidecar.writeText(text)
        val stamp = 1_700_000_000_000L
        sidecar.setLastModified(stamp)
        assertEquals(4, cache.count(sidecar, backup))
        // Same size and time: the cache must not even look at the (now garbage) content.
        sidecar.writeText("x".repeat(text.length))
        sidecar.setLastModified(stamp)
        assertEquals(4, cache.count(sidecar, backup))
    }

    @Test
    fun unreadableSidecarFallsBackToTheBackup() {
        sidecar.writeText("{ not json")
        backup.writeText(json(1, 1))
        assertEquals(2, cache.count(sidecar, backup))
        backup.writeText("also broken")
        assertEquals(0, cache.count(sidecar, backup))
    }

    @Test
    fun retainOnlyForgetsOtherSidecars() {
        val text = json(1, 0)
        sidecar.writeText(text)
        val stamp = 1_700_000_000_000L
        sidecar.setLastModified(stamp)
        assertEquals(1, cache.count(sidecar, backup))
        cache.retainOnly(emptySet())
        sidecar.writeText("x".repeat(text.length))
        sidecar.setLastModified(stamp)
        // Forgotten, so the (now garbage) file is decoded again although size and time still match.
        assertEquals(0, cache.count(sidecar, backup))
    }
}
