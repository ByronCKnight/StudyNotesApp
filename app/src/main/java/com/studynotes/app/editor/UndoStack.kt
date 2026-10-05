package com.studynotes.app.editor

import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Stroke

/**
 * One reversible annotation edit. Every command touches a single page, so applying or reverting it
 * only rebuilds that page's [PageAnnotations]; all other pages are shared between snapshots.
 *
 * Commands identify strokes and notes by id rather than by position, which keeps apply/revert
 * correct even if the page content was reordered in between.
 */
sealed interface EditCommand {
    /** 0-based index of the page this command edits. */
    val page: Int

    /** Returns [before] with this edit applied. */
    fun apply(before: PageAnnotations): PageAnnotations

    /** Returns [after] with this edit undone. */
    fun revert(after: PageAnnotations): PageAnnotations

    /** Ink strokes added by one gesture (appended on top of existing ink). */
    data class AddStrokes(override val page: Int, val strokes: List<Stroke>) : EditCommand {
        override fun apply(before: PageAnnotations): PageAnnotations {
            val existing = before.strokes.mapTo(HashSet()) { it.id }
            return before.copy(strokes = before.strokes + strokes.filter { it.id !in existing })
        }

        override fun revert(after: PageAnnotations): PageAnnotations {
            val ids = strokes.mapTo(HashSet()) { it.id }
            return after.copy(strokes = after.strokes.filter { it.id !in ids })
        }
    }

    /**
     * Strokes removed by one eraser gesture, with their positions in the page's stroke list before
     * the erase, so undo puts them back in their original z-order.
     */
    data class EraseStrokes(override val page: Int, val removed: List<IndexedValue<Stroke>>) : EditCommand {
        override fun apply(before: PageAnnotations): PageAnnotations {
            val ids = removed.mapTo(HashSet()) { it.value.id }
            return before.copy(strokes = before.strokes.filter { it.id !in ids })
        }

        override fun revert(after: PageAnnotations): PageAnnotations {
            val present = after.strokes.mapTo(HashSet()) { it.id }
            val result = after.strokes.toMutableList()
            // Ascending order: each earlier insertion restores the indices the later ones refer to.
            for ((index, stroke) in removed.sortedBy { it.index }) {
                if (stroke.id in present) continue
                result.add(index.coerceIn(0, result.size), stroke)
            }
            return after.copy(strokes = result)
        }

        companion object {
            /** Builds the command for erasing [ids] from [page] of [annotations]; null if none are present. */
            fun of(annotations: DocumentAnnotations, page: Int, ids: Set<String>): EraseStrokes? {
                val removed = annotations.page(page).strokes.withIndex().filter { it.value.id in ids }
                return if (removed.isEmpty()) null else EraseStrokes(page, removed)
            }
        }
    }

    data class AddNote(override val page: Int, val note: Note) : EditCommand {
        override fun apply(before: PageAnnotations): PageAnnotations =
            if (before.notes.any { it.id == note.id }) before else before.copy(notes = before.notes + note)

        override fun revert(after: PageAnnotations): PageAnnotations =
            after.copy(notes = after.notes.filter { it.id != note.id })
    }

    /** A deleted note and its position in the page's note list, restored there on undo. */
    data class DeleteNote(override val page: Int, val note: Note, val index: Int) : EditCommand {
        override fun apply(before: PageAnnotations): PageAnnotations =
            before.copy(notes = before.notes.filter { it.id != note.id })

        override fun revert(after: PageAnnotations): PageAnnotations {
            if (after.notes.any { it.id == note.id }) return after
            val result = after.notes.toMutableList()
            result.add(index.coerceIn(0, result.size), note)
            return after.copy(notes = result)
        }
    }

    /** Text and/or colour change of a note. */
    data class EditNote(override val page: Int, val old: Note, val new: Note) : EditCommand {
        override fun apply(before: PageAnnotations) = before.replaceNote(new)
        override fun revert(after: PageAnnotations) = after.replaceNote(old)
    }

    /** A note dragged to a new position on the same page. */
    data class MoveNote(override val page: Int, val old: Note, val new: Note) : EditCommand {
        override fun apply(before: PageAnnotations) = before.replaceNote(new)
        override fun revert(after: PageAnnotations) = after.replaceNote(old)
    }
}

private fun PageAnnotations.replaceNote(note: Note): PageAnnotations =
    copy(notes = notes.map { if (it.id == note.id) note else it })

/**
 * Linear undo/redo history over immutable [DocumentAnnotations] snapshots. Not thread-safe: the
 * editor uses it from the main thread only.
 *
 * Every state reached through the stack has a [version] id. Ids are never reused, so "has this
 * document changed since it was saved?" is simply `version != savedVersion`, and undoing back to
 * the saved state makes the document clean again.
 */
class UndoStack(
    initial: DocumentAnnotations,
    private val maxSize: Int = MAX_SIZE,
) {
    private class Entry(val command: EditCommand, val versionBefore: Long, val versionAfter: Long)

    private val undoEntries = ArrayDeque<Entry>()
    private val redoEntries = ArrayDeque<Entry>()
    private var nextVersion = INITIAL_VERSION + 1

    /** The current annotations snapshot. */
    var current: DocumentAnnotations = initial
        private set

    /** Identifies [current]; the initial state is [INITIAL_VERSION]. */
    var version: Long = INITIAL_VERSION
        private set

    val canUndo: Boolean get() = undoEntries.isNotEmpty()
    val canRedo: Boolean get() = redoEntries.isNotEmpty()

    /** Applies [command], records it (dropping the oldest entry beyond the cap) and clears redo. */
    fun push(command: EditCommand): DocumentAnnotations {
        val before = version
        current = current.updatePage(command.page, command::apply)
        version = nextVersion++
        undoEntries.addLast(Entry(command, before, version))
        while (undoEntries.size > maxSize) undoEntries.removeFirst()
        redoEntries.clear()
        return current
    }

    /** Reverts the most recent command. Returns the edited page index, or null if there was nothing to undo. */
    fun undo(): Int? {
        val entry = undoEntries.removeLastOrNull() ?: return null
        current = current.updatePage(entry.command.page, entry.command::revert)
        version = entry.versionBefore
        redoEntries.addLast(entry)
        return entry.command.page
    }

    /** Re-applies the most recently undone command. Returns the edited page index, or null if none. */
    fun redo(): Int? {
        val entry = redoEntries.removeLastOrNull() ?: return null
        current = current.updatePage(entry.command.page, entry.command::apply)
        version = entry.versionAfter
        undoEntries.addLast(entry)
        return entry.command.page
    }

    companion object {
        const val MAX_SIZE = 200
        const val INITIAL_VERSION = 0L
    }
}
