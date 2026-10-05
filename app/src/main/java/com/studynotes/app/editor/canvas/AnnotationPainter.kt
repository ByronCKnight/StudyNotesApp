package com.studynotes.app.editor.canvas

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Stroke
import kotlin.math.ceil
import kotlin.math.max

/**
 * Draws annotations onto any [Canvas] (the on-screen view, export bitmaps, thumbnails).
 *
 * CONTRACT (frozen):
 * - The caller sets up the canvas so that its current matrix maps PAGE POINTS to output pixels
 *   (e.g. canvas.translate(pageLeftPx, pageTopPx); canvas.scale(pxPerPt, pxPerPt)). [pxPerPt] is that
 *   scale, passed so the painter can pick quality settings; it must not be applied again.
 * - Instances are NOT thread-safe (they cache Paint/Path/layout objects); use one instance per thread.
 * - Highlighter strokes use a MULTIPLY blend (opaque colour), so they must be drawn onto the page
 *   content (same layer); pen strokes draw normally on top. Order: highlighters, pens, notes.
 * - Note cards are sized in page points (they scale with zoom like ink): fixed max width
 *   [NOTE_MAX_WIDTH_PT], padding, rounded corners, a soft shadow, text wrapped and ellipsized after
 *   [NOTE_MAX_LINES] lines. [noteBounds] returns exactly the drawn card rectangle (page points) and
 *   is used for hit-testing and export placement.
 */
class AnnotationPainter {

    private class CachedPath(val stroke: Stroke, val path: Path, var lastUse: Long)

    private class CachedNote(
        val text: String,
        val layout: StaticLayout,
        val widthPt: Float,
        val heightPt: Float,
        var lastUse: Long,
    )

    private val highlighterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val highlighterDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        xfermode = PorterDuffXfermode(PorterDuff.Mode.MULTIPLY)
    }
    private val penPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val penDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val noteFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val noteOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val noteShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = NOTE_TEXT_COLOR
        textSize = NOTE_TEXT_SIZE_PT * TEXT_SCALE
    }
    private val placeholderPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = NOTE_PLACEHOLDER_COLOR
        textSize = NOTE_TEXT_SIZE_PT * TEXT_SCALE
    }

    private val pathCache = HashMap<String, CachedPath>()
    private var pathTrimThreshold = PATH_CACHE_SOFT_LIMIT
    private val noteCache = HashMap<String, CachedNote>()
    private var noteTrimThreshold = NOTE_CACHE_SOFT_LIMIT
    private var useCounter = 0L
    private val cardRect = RectF()

    /**
     * Draws all strokes of [page] (highlighters first, then pens) and, if [drawNotes], its notes.
     * Strokes whose id is in [hiddenStrokeIds] are skipped.
     */
    fun drawPage(
        canvas: Canvas,
        page: PageAnnotations,
        pxPerPt: Float,
        drawNotes: Boolean = true,
        hiddenStrokeIds: Set<String> = emptySet(),
    ) {
        val strokes = page.strokes
        val checkHidden = hiddenStrokeIds.isNotEmpty()
        for (pass in 0..1) {
            val wanted = if (pass == 0) InkTool.HIGHLIGHTER else InkTool.PEN
            for (i in 0 until strokes.size) {
                val s = strokes[i]
                if (s.tool != wanted) continue
                if (checkHidden && s.id in hiddenStrokeIds) continue
                drawStroke(canvas, s, pxPerPt)
            }
        }
        if (drawNotes) {
            val notes = page.notes
            for (i in 0 until notes.size) drawNote(canvas, notes[i], pxPerPt)
        }
    }

    /** Draws one stroke (smoothed path, or a dot for a single point). */
    fun drawStroke(canvas: Canvas, stroke: Stroke, pxPerPt: Float) {
        val pts = stroke.points
        if (pts.size < 2) return
        if (pts.size < 4) {
            drawInkDot(canvas, pts[0], pts[1], stroke.tool, stroke.color, stroke.width)
            return
        }
        drawInk(canvas, pathFor(stroke), stroke.tool, stroke.color, stroke.width, stroke.flatCaps)
    }

    /**
     * Draws an arbitrary ink [path] (page points) with the stroke style of [tool]. Used for the live
     * stroke so it looks exactly like the committed one.
     */
    fun drawInk(canvas: Canvas, path: Path, tool: InkTool, color: Int, width: Float, flatCaps: Boolean = false) {
        val paint = if (tool == InkTool.HIGHLIGHTER) {
            highlighterPaint.strokeCap = if (flatCaps) Paint.Cap.BUTT else Paint.Cap.ROUND
            highlighterPaint
        } else {
            penPaint
        }
        paint.color = color or OPAQUE
        paint.strokeWidth = width
        canvas.drawPath(path, paint)
    }

    /** Draws a single-point stroke: a filled circle of the stroke width. */
    fun drawInkDot(canvas: Canvas, x: Float, y: Float, tool: InkTool, color: Int, width: Float) {
        val paint = if (tool == InkTool.HIGHLIGHTER) highlighterDotPaint else penDotPaint
        paint.color = color or OPAQUE
        canvas.drawCircle(x, y, width / 2f, paint)
    }

    /** Draws the note card: soft shadow, fill, 1 px darker outline, text (or a "Note" placeholder). */
    fun drawNote(canvas: Canvas, note: Note, pxPerPt: Float) {
        val cached = layoutFor(note)
        val left = note.x
        val top = note.y
        val right = left + cached.widthPt
        val bottom = top + cached.heightPt

        // Two faint, slightly offset layers read as a soft shadow on every canvas type (hardware
        // canvases before API 28 ignore Paint shadow layers on shapes).
        noteShadowPaint.color = SHADOW_OUTER
        cardRect.set(left - 0.6f, top, right + 0.6f, bottom + 1.6f)
        canvas.drawRoundRect(cardRect, CORNER_RADIUS_PT + 0.6f, CORNER_RADIUS_PT + 0.6f, noteShadowPaint)
        noteShadowPaint.color = SHADOW_INNER
        cardRect.set(left - 0.2f, top + 0.2f, right + 0.2f, bottom + 0.8f)
        canvas.drawRoundRect(cardRect, CORNER_RADIUS_PT + 0.2f, CORNER_RADIUS_PT + 0.2f, noteShadowPaint)

        cardRect.set(left, top, right, bottom)
        noteFillPaint.color = note.color or OPAQUE
        canvas.drawRoundRect(cardRect, CORNER_RADIUS_PT, CORNER_RADIUS_PT, noteFillPaint)

        val outlineWidth = 1f / pxPerPt.coerceAtLeast(MIN_SCALE)
        val half = outlineWidth / 2f
        noteOutlinePaint.color = darker(note.color)
        noteOutlinePaint.strokeWidth = outlineWidth
        cardRect.set(left + half, top + half, right - half, bottom - half)
        canvas.drawRoundRect(cardRect, CORNER_RADIUS_PT - half, CORNER_RADIUS_PT - half, noteOutlinePaint)

        val save = canvas.save()
        canvas.translate(left + PADDING_PT, top + PADDING_PT)
        canvas.scale(1f / TEXT_SCALE, 1f / TEXT_SCALE)
        cached.layout.draw(canvas)
        canvas.restoreToCount(save)
    }

    /** Card rectangle of [note] in page points. */
    fun noteBounds(note: Note): RectF = RectF().also { noteBoundsInto(note, it) }

    /** Allocation-free variant of [noteBounds]: writes the card rectangle into [out]. */
    fun noteBoundsInto(note: Note, out: RectF) {
        val cached = layoutFor(note)
        out.set(note.x, note.y, note.x + cached.widthPt, note.y + cached.heightPt)
    }

    /** Drops every cached path and text layout (e.g. when another document is shown). */
    fun clearCaches() {
        pathCache.clear()
        noteCache.clear()
        pathTrimThreshold = PATH_CACHE_SOFT_LIMIT
        noteTrimThreshold = NOTE_CACHE_SOFT_LIMIT
    }

    private fun pathFor(stroke: Stroke): Path {
        val use = ++useCounter
        val cached = pathCache[stroke.id]
        if (cached != null && cached.stroke === stroke) {
            cached.lastUse = use
            return cached.path
        }
        // Reuse the old Path object when a stroke instance was replaced (same id, new data).
        val path = cached?.path ?: Path()
        buildSmoothPath(stroke.points, stroke.pointCount, path)
        pathCache[stroke.id] = CachedPath(stroke, path, use)
        if (pathCache.size > pathTrimThreshold) {
            trimPaths()
        }
        return path
    }

    /** Removes paths not drawn recently (strokes that were erased, undone or belong to other pages). */
    private fun trimPaths() {
        val cutoff = useCounter - 2L * pathCache.size
        pathCache.values.removeAll { it.lastUse < cutoff }
        pathTrimThreshold = max(PATH_CACHE_SOFT_LIMIT, pathCache.size * 2)
    }

    private fun layoutFor(note: Note): CachedNote {
        val use = ++useCounter
        val cached = noteCache[note.id]
        if (cached != null && cached.text == note.text) {
            cached.lastUse = use
            return cached
        }
        val built = buildNoteLayout(note.text, use)
        noteCache[note.id] = built
        if (noteCache.size > noteTrimThreshold) {
            val cutoff = useCounter - 4L * noteCache.size
            noteCache.values.removeAll { it.lastUse < cutoff }
            noteTrimThreshold = max(NOTE_CACHE_SOFT_LIMIT, noteCache.size * 2)
        }
        return built
    }

    private fun buildNoteLayout(text: String, use: Long): CachedNote {
        val empty = text.isBlank()
        val content: CharSequence = if (empty) PLACEHOLDER else text
        val paint = if (empty) placeholderPaint else textPaint
        val maxInner = ((NOTE_MAX_WIDTH_PT - 2f * PADDING_PT) * TEXT_SCALE).toInt()
        var layout = staticLayout(content, paint, maxInner)
        var widest = 0f
        var rtl = false
        for (i in 0 until layout.lineCount) {
            widest = max(widest, layout.getLineWidth(i))
            if (layout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT) rtl = true
        }
        val inner = ceil(widest).toInt().coerceIn(1, maxInner)
        // Right-to-left paragraphs align to the layout's right edge: re-lay out at the card's inner
        // width so the text stays inside a card narrower than the maximum.
        if (rtl && inner < maxInner) layout = staticLayout(content, paint, inner)
        val widthPt = (inner / TEXT_SCALE + 2f * PADDING_PT).coerceIn(NOTE_MIN_WIDTH_PT, NOTE_MAX_WIDTH_PT)
        val heightPt = layout.height / TEXT_SCALE + 2f * PADDING_PT
        return CachedNote(text, layout, widthPt, heightPt, use)
    }

    private fun staticLayout(text: CharSequence, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0f, 1.08f)
            .setMaxLines(NOTE_MAX_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()

    private fun darker(color: Int): Int {
        val r = ((color shr 16) and 0xFF) * 78 / 100
        val g = ((color shr 8) and 0xFF) * 78 / 100
        val b = (color and 0xFF) * 78 / 100
        return OPAQUE or (r shl 16) or (g shl 8) or b
    }

    companion object {
        const val NOTE_MAX_WIDTH_PT = 150f
        const val NOTE_MAX_LINES = 8
        const val NOTE_TEXT_SIZE_PT = 8.5f

        /** Minimum card width in page points. */
        const val NOTE_MIN_WIDTH_PT = 36f
        private const val PADDING_PT = 6f
        private const val CORNER_RADIUS_PT = 4f

        /**
         * Text is laid out at 10× size and drawn scaled down: metrics of an 8.5 px font are coarse,
         * and the larger layout keeps line breaks identical at every zoom and output resolution.
         */
        private const val TEXT_SCALE = 10f
        private const val MIN_SCALE = 0.05f

        private const val OPAQUE = 0xFF000000.toInt()
        private const val NOTE_TEXT_COLOR = 0xFF1F1F24.toInt()
        private const val NOTE_PLACEHOLDER_COLOR = 0xFF8A8A94.toInt()
        private const val SHADOW_OUTER = 0x10000000
        private const val SHADOW_INNER = 0x18000000
        private const val PLACEHOLDER = "Note"

        private const val PATH_CACHE_SOFT_LIMIT = 2048
        private const val NOTE_CACHE_SOFT_LIMIT = 256

        /**
         * Builds the smoothed ink path for the first [count] points of [points]: quadratic Béziers
         * through the midpoints of consecutive points, starting at the first and ending at the last
         * point. The live stroke in [DocumentCanvasView] uses the same construction incrementally.
         */
        fun buildSmoothPath(points: FloatArray, count: Int, path: Path) {
            path.rewind()
            if (count <= 0) return
            path.moveTo(points[0], points[1])
            if (count == 1) return
            for (i in 1 until count - 1) {
                val x = points[2 * i]
                val y = points[2 * i + 1]
                path.quadTo(x, y, (x + points[2 * i + 2]) / 2f, (y + points[2 * i + 3]) / 2f)
            }
            path.lineTo(points[2 * count - 2], points[2 * count - 1])
        }
    }
}
