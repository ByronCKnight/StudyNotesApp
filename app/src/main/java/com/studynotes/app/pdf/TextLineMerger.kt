package com.studynotes.app.pdf

/** An axis-aligned rectangle in page points (y down). Pure stand-in for RectF so merging is unit-testable. */
internal data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top

    /** False for empty, inverted or NaN/infinite boxes, which PDFium occasionally reports for spaces. */
    val isValid: Boolean
        get() = left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            right > left && bottom > top

    fun union(other: TextBox) = TextBox(
        minOf(left, other.left),
        minOf(top, other.top),
        maxOf(right, other.right),
        maxOf(bottom, other.bottom),
    )
}

/**
 * Turns the raw rectangles of a text selection into one rectangle per text line.
 *
 * PDFium reports a rectangle per run of characters with the same font/size, so one line of text
 * with a bold word or a superscript arrives as several boxes. Two boxes belong to the same line
 * when their vertical overlap is at least half the height of the smaller one.
 */
internal object TextLineMerger {

    private const val MIN_OVERLAP_FRACTION = 0.5f

    private val TOP_TO_BOTTOM = compareBy<TextBox>({ it.top }, { it.left })

    /** Drops invalid boxes, merges boxes on the same line, and returns the lines sorted top-to-bottom. */
    fun merge(boxes: List<TextBox>): List<TextBox> {
        var lines = boxes.filter { it.isValid }.sortedWith(TOP_TO_BOTTOM)
        // A merge grows a line's box, which could bring it into overlap with a line it was already
        // compared with, so repeat until a pass merges nothing. Boxes arrive sorted by top, so in
        // practice the second pass is just the confirming no-op; selections hold a few hundred
        // boxes at most.
        while (true) {
            val merged = ArrayList<TextBox>(lines.size)
            for (box in lines) {
                val line = merged.indexOfLast { sameLine(it, box) }
                if (line >= 0) merged[line] = merged[line].union(box) else merged += box
            }
            if (merged.size == lines.size) return merged
            lines = merged.sortedWith(TOP_TO_BOTTOM)
        }
    }

    private fun sameLine(a: TextBox, b: TextBox): Boolean {
        val overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return overlap >= MIN_OVERLAP_FRACTION * minOf(a.height, b.height)
    }
}
