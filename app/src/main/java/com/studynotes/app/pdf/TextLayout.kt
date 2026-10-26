package com.studynotes.app.pdf

import kotlin.math.max

/** An axis-aligned rectangle in page points (y down). Pure stand-in for RectF so the layout is unit-testable. */
internal data class TextBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val height: Float get() = bottom - top
    val width: Float get() = right - left

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

/** A word on a page: its box (page points) and its character range (stop exclusive) in the page text. */
internal class TextWord(val box: TextBox, val from: Int, val until: Int)

/** One line of text: its box and its words in reading order along the line. */
internal class TextLine(val box: TextBox, val words: List<TextWord>)

/** What a drag selects: one box per line in reading order, and the line the drag ended on. */
internal class TextSelection(val boxes: List<TextBox>, val endLine: Int)

/**
 * The geometry of a page's text: its lines in reading order, each with its words. Built once per
 * page from the PDF engine (see `ModernBackend`), after which [select] answers instantly and
 * deterministically, so a selection preview that follows the pen never flickers: it only changes
 * when the pen crosses a word or line boundary.
 *
 * Text that runs down or up the screen (a page with /Rotate 90 or 270) is handled by swapping x
 * and y on the way in and out, so "along the line" below is always x.
 */
internal class PageTextLayout(lines: List<TextLine>) {

    /** True when the lines of text run vertically on the displayed page. */
    val isVertical: Boolean

    /** The lines in reading order, transposed when [isVertical]. */
    private val rows: List<TextLine>

    init {
        // Lines with several words decide the orientation; a lone short word is taller than wide anyway.
        var vote = 0
        for (line in lines) vote += if (line.box.height > line.box.width) line.words.size else -line.words.size
        isVertical = vote > 0
        rows = if (isVertical) lines.map { transpose(it) } else lines
    }

    val lines: List<TextLine> get() = if (isVertical) rows.map { transpose(it) } else rows

    val lineCount: Int get() = rows.size

    /**
     * The text a drag from ([startX], [startY]) to ([endX], [endY]) (page points) selects, or null
     * when the start is not on a line of text. The start must lie on a line (within [START_SLACK]
     * of its height outside it); the end goes to the nearest line, so dragging past the margin
     * still extends the selection. On the start line the selection runs from the word under the
     * pen (or the next one) onward, every line between is whole, and the end line is taken up to
     * the word under the pen; on one line, the words the span touches.
     *
     * [previousEndLine] (from the last result, or -1) keeps the end on its line while the pen stays
     * near it, so a wobble between two lines does not toggle the selection.
     */
    fun select(startX: Float, startY: Float, endX: Float, endY: Float, previousEndLine: Int = -1): TextSelection? {
        if (rows.isEmpty()) return null
        val sx = if (isVertical) startY else startX
        val sy = if (isVertical) startX else startY
        val ex = if (isVertical) endY else endX
        val ey = if (isVertical) endX else endY
        val a = lineNear(sx, sy, START_SLACK) ?: return null
        var b = lineNear(ex, ey, Float.POSITIVE_INFINITY) ?: return null
        if (previousEndLine in rows.indices && previousEndLine != b) {
            val kept = rows[previousEndLine].box
            if (outside(ey, kept.top, kept.bottom) <= STICKY_SLACK * kept.height) b = previousEndLine
        }
        val boxes = ArrayList<TextBox>()
        if (a == b) {
            val lo = minOf(sx, ex)
            val hi = maxOf(sx, ex)
            spanOf(rows[a]) { it.box.right > lo && it.box.left < hi }?.let(boxes::add)
        } else {
            // Reading order: from the earlier of the two points to the later one.
            val forward = a < b
            val first = if (forward) a else b
            val last = if (forward) b else a
            val firstX = if (forward) sx else ex
            val lastX = if (forward) ex else sx
            spanOf(rows[first]) { it.box.right > firstX }?.let(boxes::add)
            for (i in first + 1 until last) spanOf(rows[i]) { true }?.let(boxes::add)
            spanOf(rows[last]) { it.box.left < lastX }?.let(boxes::add)
        }
        return TextSelection(if (isVertical) boxes.map { transpose(it) } else boxes, b)
    }

    /**
     * Where the text from character [start] until [end] (stop exclusive; indices into the page
     * text the layout was built from, as a [TextSearch] hit) lies on the page: one box per line in
     * reading order, each the line's full height across the words the range touches. A word the
     * range only partly covers contributes that part of its box, in proportion to its characters,
     * so a hit inside a longer word is marked where it is. Empty when no measured word is in range.
     */
    fun boxesOf(start: Int, end: Int): List<TextBox> {
        if (end <= start) return emptyList()
        val out = ArrayList<TextBox>()
        for (row in rows) {
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (w in row.words) {
                if (w.until <= start || w.from >= end || w.until <= w.from) continue
                val length = (w.until - w.from).toFloat()
                val f0 = (start - w.from).coerceAtLeast(0) / length
                val f1 = (end - w.from).coerceAtMost(w.until - w.from) / length
                left = minOf(left, w.box.left + w.box.width * f0)
                right = maxOf(right, w.box.left + w.box.width * f1)
            }
            if (right > left) out += TextBox(left, row.box.top, right, row.box.bottom)
        }
        return if (isVertical) out.map { transpose(it) } else out
    }

    /**
     * The line nearest to a point: its band (top..bottom) must be within [slack] × its height of
     * [y]; among those, the one closest to the point, horizontal distance counting a quarter (so
     * the column under the pen wins, but a point in the margin still finds the line beside it).
     */
    private fun lineNear(x: Float, y: Float, slack: Float): Int? {
        var best = -1
        var bestDistance = Float.POSITIVE_INFINITY
        for (i in rows.indices) {
            val box = rows[i].box
            val dy = outside(y, box.top, box.bottom)
            if (dy > slack * box.height) continue
            val d = dy + HORIZONTAL_WEIGHT * outside(x, box.left, box.right)
            if (d < bestDistance) {
                bestDistance = d
                best = i
            }
        }
        return best.takeIf { it >= 0 }
    }

    /** The box over [line]'s words that pass [include]: the line's height, the words' extent. */
    private inline fun spanOf(line: TextLine, include: (TextWord) -> Boolean): TextBox? {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (w in line.words) {
            if (!include(w)) continue
            left = minOf(left, w.box.left)
            right = maxOf(right, w.box.right)
        }
        if (right <= left) return null
        return TextBox(left, line.box.top, right, line.box.bottom)
    }

    private fun outside(v: Float, lo: Float, hi: Float): Float = max(0f, max(lo - v, v - hi))

    private fun transpose(box: TextBox) = TextBox(box.top, box.left, box.bottom, box.right)

    private fun transpose(line: TextLine) = TextLine(transpose(line.box), line.words.map { TextWord(transpose(it.box), it.from, it.until) })

    private companion object {
        /** How far outside a line's band (in line heights) the start of a drag still counts as on it. */
        const val START_SLACK = 0.6f

        /** How far outside the current end line's band (in line heights) the pen may wander before the end moves. */
        const val STICKY_SLACK = 0.35f

        const val HORIZONTAL_WEIGHT = 0.25f
    }
}

/** Splits a page's text into lines and words, and groups measured words into lines. Pure logic. */
internal object TextLayoutBuilder {

    /** Character ranges (stop exclusive) of the lines of [text]: split at CR, LF or CRLF; blank lines left out. */
    fun lineRanges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        var i = 0
        while (i <= text.length) {
            val atBreak = i == text.length || text[i] == '\n' || text[i] == '\r'
            if (atBreak) {
                if (i > start && (start until i).any { !text[it].isWhitespace() }) out += start until i
                if (i < text.length && text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                start = i + 1
            }
            i++
        }
        return out
    }

    /** Character ranges (stop exclusive) of the runs of non-whitespace within [range] of [text]. */
    fun wordRanges(text: String, range: IntRange): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (i in range.first..range.last + 1) {
            val inWord = i <= range.last && !text[i].isWhitespace()
            if (inWord && start < 0) start = i
            if (!inWord && start >= 0) {
                out += start until i
                start = -1
            }
        }
        return out
    }

    /**
     * Groups measured [words] (one PDF-engine line, in text order) into lines: words whose boxes
     * overlap vertically by at least half the smaller height share a line (a line the engine did not
     * split, or a superscript), each sorted left to right. [vertical] text groups by horizontal
     * overlap and sorts top to bottom instead.
     */
    fun linesOf(words: List<TextWord>, vertical: Boolean): List<TextLine> {
        val groups = ArrayList<ArrayList<TextWord>>()
        for (w in words) {
            val group = groups.lastOrNull { g -> g.any { sameLine(it.box, w.box, vertical) } }
            if (group != null) group += w else groups += arrayListOf(w)
        }
        return groups.map { g ->
            val sorted = if (vertical) g.sortedBy { it.box.top } else g.sortedBy { it.box.left }
            TextLine(g.map { it.box }.reduce(TextBox::union), sorted)
        }
    }

    private fun sameLine(a: TextBox, b: TextBox, vertical: Boolean): Boolean {
        val overlap: Float
        val smaller: Float
        if (vertical) {
            overlap = minOf(a.right, b.right) - maxOf(a.left, b.left)
            smaller = minOf(a.right - a.left, b.right - b.left)
        } else {
            overlap = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
            smaller = minOf(a.height, b.height)
        }
        return overlap >= 0.5f * smaller
    }
}
