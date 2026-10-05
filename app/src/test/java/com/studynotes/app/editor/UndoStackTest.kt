package com.studynotes.app.editor

import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Stroke
import com.studynotes.app.model.ViewState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private fun stroke(id: String, group: String? = null) =
        Stroke(id, InkTool.HIGHLIGHTER, 0xFFFFE94D.toInt(), 14f, floatArrayOf(0f, 0f, 10f, 0f), groupId = group)

    private fun note(id: String, x: Float = 10f, y: Float = 20f, text: String = "text $id") =
        Note(id, x, y, text, 0xFFFFF3A6.toInt(), 1L, 1L)

    private fun strokeIds(stack: UndoStack, page: Int) = stack.current.page(page).strokes.map { it.id }
    private fun noteIds(stack: UndoStack, page: Int) = stack.current.page(page).notes.map { it.id }

    @Test
    fun addStrokesApplyUndoRedo() {
        val stack = UndoStack(DocumentAnnotations.EMPTY)
        assertFalse(stack.canUndo)
        stack.push(EditCommand.AddStrokes(2, listOf(stroke("a"), stroke("b"))))
        assertEquals(listOf("a", "b"), strokeIds(stack, 2))
        assertTrue(stack.canUndo)

        assertEquals(2, stack.undo())
        assertTrue(stack.current.isEmpty)
        assertFalse("empty pages are dropped", stack.current.pages.containsKey(2))
        assertTrue(stack.canRedo)

        assertEquals(2, stack.redo())
        assertEquals(listOf("a", "b"), strokeIds(stack, 2))
        assertFalse(stack.canRedo)
    }

    @Test
    fun eraseRestoresOriginalOrderOnUndo() {
        val initial = DocumentAnnotations(
            pages = mapOf(0 to PageAnnotations(strokes = listOf("s0", "s1", "s2", "s3", "s4").map { stroke(it) })),
        )
        val stack = UndoStack(initial)
        val erase = EditCommand.EraseStrokes.of(stack.current, 0, setOf("s1", "s3", "s4", "missing"))!!
        assertEquals(listOf(1, 3, 4), erase.removed.map { it.index })

        stack.push(erase)
        assertEquals(listOf("s0", "s2"), strokeIds(stack, 0))
        stack.undo()
        assertEquals(listOf("s0", "s1", "s2", "s3", "s4"), strokeIds(stack, 0))
        stack.redo()
        assertEquals(listOf("s0", "s2"), strokeIds(stack, 0))
    }

    @Test
    fun eraseOfAbsentStrokesIsNoCommand() {
        assertNull(EditCommand.EraseStrokes.of(DocumentAnnotations.EMPTY, 0, setOf("x")))
    }

    @Test
    fun eraseUndoAfterLaterAdditionsStillReinsertsInPlace() {
        val initial = DocumentAnnotations(pages = mapOf(0 to PageAnnotations(strokes = listOf(stroke("a"), stroke("b")))))
        val erase = EditCommand.EraseStrokes.of(initial, 0, setOf("a"))!!
        // Reverting onto a page that gained a stroke keeps the erased one at its old index.
        val page = erase.revert(PageAnnotations(strokes = listOf(stroke("b"), stroke("c"))))
        assertEquals(listOf("a", "b", "c"), page.strokes.map { it.id })
    }

    @Test
    fun addAndDeleteNote() {
        val stack = UndoStack(DocumentAnnotations(pages = mapOf(1 to PageAnnotations(notes = listOf(note("n1"), note("n2"), note("n3"))))))
        stack.push(EditCommand.AddNote(1, note("n4")))
        assertEquals(listOf("n1", "n2", "n3", "n4"), noteIds(stack, 1))

        stack.push(EditCommand.DeleteNote(1, note("n2"), 1))
        assertEquals(listOf("n1", "n3", "n4"), noteIds(stack, 1))

        stack.undo()
        assertEquals("deleted note goes back to its index", listOf("n1", "n2", "n3", "n4"), noteIds(stack, 1))
        stack.undo()
        assertEquals(listOf("n1", "n2", "n3"), noteIds(stack, 1))
        stack.redo()
        stack.redo()
        assertEquals(listOf("n1", "n3", "n4"), noteIds(stack, 1))
    }

    @Test
    fun editAndMoveNote() {
        val original = note("n1", x = 5f, y = 6f, text = "old")
        val stack = UndoStack(DocumentAnnotations(pages = mapOf(0 to PageAnnotations(notes = listOf(original, note("n2"))))))

        val edited = original.copy(text = "new", color = 0xFFCFE7FF.toInt())
        stack.push(EditCommand.EditNote(0, original, edited))
        assertEquals(edited, stack.current.page(0).notes[0])

        val moved = edited.copy(x = 50f, y = 60f)
        stack.push(EditCommand.MoveNote(0, edited, moved))
        assertEquals(moved, stack.current.page(0).notes[0])
        assertEquals("order is preserved", listOf("n1", "n2"), noteIds(stack, 0))

        stack.undo()
        assertEquals(edited, stack.current.page(0).notes[0])
        stack.undo()
        assertEquals(original, stack.current.page(0).notes[0])
        stack.redo()
        stack.redo()
        assertEquals(moved, stack.current.page(0).notes[0])
    }

    @Test
    fun commandsOnlyTouchTheirPageAndKeepViewState() {
        val other = PageAnnotations(strokes = listOf(stroke("keep")))
        val viewState = ViewState(page = 3, pageOffsetFraction = 0.5f, zoom = 2f)
        val stack = UndoStack(DocumentAnnotations(pages = mapOf(5 to other), viewState = viewState))
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("new"))))
        assertSame(other, stack.current.pages[5])
        assertEquals(viewState, stack.current.viewState)
    }

    @Test
    fun newEditClearsRedo() {
        val stack = UndoStack(DocumentAnnotations.EMPTY)
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("a"))))
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("b"))))
        stack.undo()
        assertTrue(stack.canRedo)
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("c"))))
        assertFalse(stack.canRedo)
        assertNull(stack.redo())
        assertEquals(listOf("a", "c"), strokeIds(stack, 0))
    }

    @Test
    fun historyIsCappedAtMaxSize() {
        val stack = UndoStack(DocumentAnnotations.EMPTY)
        repeat(UndoStack.MAX_SIZE + 25) { i -> stack.push(EditCommand.AddStrokes(0, listOf(stroke("s$i")))) }
        var undone = 0
        while (stack.undo() != null) undone++
        assertEquals(UndoStack.MAX_SIZE, undone)
        // The 25 oldest edits can no longer be undone.
        assertEquals((0 until 25).map { "s$it" }, strokeIds(stack, 0))
    }

    @Test
    fun customCapIsHonoured() {
        val stack = UndoStack(DocumentAnnotations.EMPTY, maxSize = 2)
        repeat(5) { i -> stack.push(EditCommand.AddStrokes(0, listOf(stroke("s$i")))) }
        assertEquals(0, stack.undo())
        assertEquals(0, stack.undo())
        assertNull(stack.undo())
    }

    @Test
    fun versionTracksDirtyState() {
        val stack = UndoStack(DocumentAnnotations.EMPTY)
        val saved = stack.version
        assertEquals(UndoStack.INITIAL_VERSION, saved)

        stack.push(EditCommand.AddStrokes(0, listOf(stroke("a"))))
        val afterA = stack.version
        assertNotEquals(saved, afterA)

        stack.undo()
        assertEquals("undo back to the saved state is clean again", saved, stack.version)
        stack.redo()
        assertEquals("redo returns to the same state id", afterA, stack.version)

        stack.undo()
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("b"))))
        assertNotEquals("a different edit never reuses an old id", afterA, stack.version)
        assertNotEquals(saved, stack.version)
    }

    @Test
    fun savingMidHistoryMakesThatStateClean() {
        val stack = UndoStack(DocumentAnnotations.EMPTY)
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("a"))))
        stack.push(EditCommand.AddStrokes(0, listOf(stroke("b"))))
        val savedVersion = stack.version
        stack.undo()
        assertNotEquals(savedVersion, stack.version)
        stack.redo()
        assertEquals(savedVersion, stack.version)
    }

    @Test
    fun addingAnExistingStrokeOrNoteTwiceIsIdempotent() {
        val page = PageAnnotations(strokes = listOf(stroke("a")), notes = listOf(note("n")))
        assertEquals(1, EditCommand.AddStrokes(0, listOf(stroke("a"))).apply(page).strokes.size)
        assertEquals(1, EditCommand.AddNote(0, note("n")).apply(page).notes.size)
        assertEquals(1, EditCommand.DeleteNote(0, note("n"), 0).revert(page).notes.size)
    }
}
