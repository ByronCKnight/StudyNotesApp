package com.studynotes.app.model

import java.util.UUID

/**
 * Annotation model. Everything is immutable: an edit produces a new [DocumentAnnotations] snapshot,
 * which makes undo/redo, autosave snapshots and cross-thread hand-off trivial.
 *
 * Coordinate space ("page space"): PDF points (1/72 inch) of the page *as displayed* by
 * android.graphics.pdf.PdfRenderer — i.e. the crop box with /Rotate applied, origin at the
 * top-left corner, x to the right, y downwards. Page width/height in this space are
 * PdfRenderer.Page.getWidth()/getHeight().
 */

enum class InkTool { HIGHLIGHTER, PEN }

/**
 * One ink stroke. [points] holds interleaved x,y pairs in page space and must not be mutated
 * after construction. Highlighter strokes are drawn with a MULTIPLY blend so text stays readable.
 */
class Stroke(
    val id: String,
    val tool: InkTool,
    /** Opaque ARGB colour. */
    val color: Int,
    /** Stroke width in page points. */
    val width: Float,
    /** Interleaved x0,y0,x1,y1,... in page points. At least one point (2 floats). */
    val points: FloatArray,
    /** Butt caps instead of round — used by highlights snapped to text lines. */
    val flatCaps: Boolean = false,
    /** Strokes created by one gesture (e.g. a text-snapped highlight spanning several lines) share a group. */
    val groupId: String? = null,
) {
    val pointCount: Int get() = points.size / 2

    override fun equals(other: Any?): Boolean =
        other is Stroke && other.id == id && other.tool == tool && other.color == color &&
            other.width == width && other.flatCaps == flatCaps && other.groupId == groupId &&
            other.points.contentEquals(points)

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "Stroke(id=$id, tool=$tool, points=$pointCount)"
}

/** A typed sticky note. ([x],[y]) is the top-left corner of the note card in page space. */
data class Note(
    val id: String,
    val x: Float,
    val y: Float,
    val text: String,
    /** Opaque ARGB background colour of the note card. */
    val color: Int,
    val createdAt: Long,
    val modifiedAt: Long,
)

data class PageAnnotations(
    val strokes: List<Stroke> = emptyList(),
    val notes: List<Note> = emptyList(),
) {
    val isEmpty: Boolean get() = strokes.isEmpty() && notes.isEmpty()

    companion object {
        val EMPTY = PageAnnotations()
    }
}

/** Where the reader was, restored when the document is reopened. */
data class ViewState(
    /** Page whose area contains the top of the viewport. */
    val page: Int = 0,
    /** How far down that page the viewport top is, 0..1 of the page height. */
    val pageOffsetFraction: Float = 0f,
    /** Zoom relative to fit-width (1.0 = page width fills the view). */
    val zoom: Float = 1f,
)

data class DocumentAnnotations(
    /** Only pages that have annotations are present. Keys are 0-based page indices. */
    val pages: Map<Int, PageAnnotations> = emptyMap(),
    val viewState: ViewState = ViewState(),
) {
    fun page(index: Int): PageAnnotations = pages[index] ?: PageAnnotations.EMPTY

    /** Returns a copy with page [index] replaced by [transform] applied to it; empty pages are dropped. */
    fun updatePage(index: Int, transform: (PageAnnotations) -> PageAnnotations): DocumentAnnotations {
        val updated = transform(page(index))
        val newPages = if (updated.isEmpty) pages - index else pages + (index to updated)
        return copy(pages = newPages)
    }

    val strokeCount: Int get() = pages.values.sumOf { it.strokes.size }
    val noteCount: Int get() = pages.values.sumOf { it.notes.size }
    val isEmpty: Boolean get() = pages.values.all { it.isEmpty }

    companion object {
        val EMPTY = DocumentAnnotations()
    }
}

fun newAnnotationId(): String = UUID.randomUUID().toString()
