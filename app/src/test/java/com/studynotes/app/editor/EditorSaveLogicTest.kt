package com.studynotes.app.editor

import com.studynotes.app.model.DocType
import com.studynotes.app.model.Note
import com.studynotes.app.model.SourceInfo
import com.studynotes.app.model.ViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Pure decisions behind the editor's saving: reading position, draft notes, sidecar matching, library membership. */
class EditorSaveLogicTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- Reading position (data-safety-6)

    @Test
    fun noSidecarAndStillAtTheStartIsNotWorthWriting() {
        assertFalse(viewStateMoved(null, ViewState()))
    }

    @Test
    fun noSidecarButScrolledIsWorthWriting() {
        assertTrue(viewStateMoved(null, ViewState(page = 140)))
        assertTrue(viewStateMoved(null, ViewState(page = 0, pageOffsetFraction = 0.5f)))
    }

    @Test
    fun anotherPageOrZoomCounts() {
        val saved = ViewState(page = 12, pageOffsetFraction = 0.3f, zoom = 1f)
        assertTrue(viewStateMoved(saved, saved.copy(page = 13)))
        assertTrue(viewStateMoved(saved, saved.copy(zoom = 2.5f)))
        assertTrue(viewStateMoved(saved, saved.copy(pageOffsetFraction = 0.6f)))
    }

    @Test
    fun jitterDoesNotCount() {
        val saved = ViewState(page = 12, pageOffsetFraction = 0.3f, zoom = 1f)
        assertFalse(viewStateMoved(saved, saved))
        assertFalse(viewStateMoved(saved, saved.copy(pageOffsetFraction = 0.305f, zoom = 1.01f)))
    }

    // ---- Draft notes committed on background (data-safety-9)

    private val placed = Note("n1", 10f, 20f, "", 0xFFFFF3A6.toInt(), 100L, 100L)

    @Test
    fun blankDraftCommitsNothing() {
        val request = NoteEditorRequest(0, placed, isNew = true)
        assertNull(plannedDraftNote(request, null, "   \n", placed.color, 500L))
    }

    @Test
    fun newNoteDraftBecomesTheNoteWithTrimmedText() {
        val request = NoteEditorRequest(3, placed, isNew = true)
        val note = plannedDraftNote(request, null, "  Ask about mitosis \n", 0xFFCFE7FF.toInt(), 500L)!!
        assertEquals("n1", note.id)
        assertEquals("Ask about mitosis", note.text)
        assertEquals(0xFFCFE7FF.toInt(), note.color)
        assertEquals(10f, note.x)
        assertEquals(20f, note.y)
    }

    @Test
    fun existingNoteDraftEditsTheCurrentVersion() {
        val current = placed.copy(text = "Old", x = 50f)
        val request = NoteEditorRequest(0, placed.copy(text = "Old"), isNew = false)
        val note = plannedDraftNote(request, current, "New text", current.color, 900L)!!
        assertEquals("New text", note.text)
        assertEquals(50f, note.x)
        assertEquals(900L, note.modifiedAt)
        assertEquals(current.createdAt, note.createdAt)
    }

    @Test
    fun unchangedOrMissingExistingNoteCommitsNothing() {
        val current = placed.copy(text = "Same")
        val request = NoteEditorRequest(0, current, isNew = false)
        assertNull(plannedDraftNote(request, current, " Same ", current.color, 900L))
        assertNull(plannedDraftNote(request, null, "Other", current.color, 900L))
    }

    @Test
    fun colourChangeAloneIsCommitted() {
        val current = placed.copy(text = "Same")
        val request = NoteEditorRequest(0, current, isNew = false)
        assertEquals(0xFFD6F5CF.toInt(), plannedDraftNote(request, current, "Same", 0xFFD6F5CF.toInt(), 900L)!!.color)
    }

    // ---- Sidecar written for this PDF? (data-safety-3, editor part)

    @Test
    fun sidecarMatchesSameTypeAndSizeEvenWithNewTimestamp() {
        val saved = SourceInfo("Lecture.pdf", DocType.PDF, size = 12_345L, lastModified = 1L)
        assertTrue(sidecarMatchesSource(saved, DocType.PDF, 12_345L))
    }

    @Test
    fun sidecarOfADifferentFileDoesNotMatch() {
        val saved = SourceInfo("Lecture.pdf", DocType.PDF, size = 12_345L, lastModified = 1L)
        assertFalse(sidecarMatchesSource(saved, DocType.PDF, 99_999L))
        assertFalse(sidecarMatchesSource(saved, DocType.DOCX, 12_345L))
    }

    // ---- Document still in the library it was opened from (data-safety-10)

    @Test
    fun documentDirectlyInRootIsInside() {
        val root = tmp.newFolder("StudyNotes")
        val doc = File(root, "Lecture.pdf").apply { writeText("%PDF-") }
        assertTrue(isDocumentIn(doc, root))
        assertTrue(isDocumentIn(File(root, "./Lecture.pdf"), File(root.path + File.separator)))
    }

    @Test
    fun documentInAnotherLibraryOrMissingIsNotInside() {
        val privateRoot = tmp.newFolder("private", "StudyNotes")
        val publicRoot = tmp.newFolder("public", "StudyNotes")
        val doc = File(privateRoot, "Lecture.pdf").apply { writeText("%PDF-") }
        assertFalse(isDocumentIn(doc, publicRoot))
        assertFalse(isDocumentIn(File(publicRoot, "Lecture.pdf"), publicRoot))
    }

    @Test
    fun documentInASubfolderIsNotInside() {
        val root = tmp.newFolder("lib")
        val sub = File(root, ".studynotes").apply { mkdirs() }
        val doc = File(sub, "x.pdf").apply { writeText("%PDF-") }
        assertFalse(isDocumentIn(doc, root))
    }
}
