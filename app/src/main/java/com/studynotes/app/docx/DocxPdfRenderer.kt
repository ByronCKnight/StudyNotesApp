package com.studynotes.app.docx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.BitmapRegionDecoder
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.LeadingMarginSpan
import android.text.style.LineHeightSpan
import android.text.style.MetricAffectingSpan
import android.text.style.ReplacementSpan
import android.text.style.TabStopSpan
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Lays out a [DocxDocument] with [StaticLayout] and draws it into a [PdfDocument], producing real
 * (selectable) text.
 *
 * Units: all layout happens in "units" of 1/[U] point. StaticLayout's line metrics are integers, and
 * whole points would visibly distort line heights of small fonts; every page canvas is scaled by
 * 1/[U], so the PDF itself stays in points as PdfDocument expects.
 *
 * Flow: blocks become [Flow] items lazily (one block of look-ahead for keep-with-next), which the
 * [Paginator] places on pages: paragraphs split at line boundaries (with widow/orphan control),
 * tables move whole rows to the next page, and rows taller than a page are sliced across pages.
 *
 * [checkpoint] is called between blocks and pages; it may throw to cancel the conversion.
 */
internal class DocxPdfRenderer(
    private val doc: DocxDocument,
    private val loadImage: (entryName: String) -> ByteArray?,
    private val checkpoint: () -> Unit,
) {
    /** Page size and content box of one section, in PDF points (page) and layout units (the rest). */
    private class PageGeometry(val setup: DocxPageSetup) {
        val widthPt = setup.widthPt.roundToInt().coerceAtLeast(1)
        val heightPt = setup.heightPt.roundToInt().coerceAtLeast(1)
        val originX = setup.marginLeftPt * U
        val originY = setup.marginTopPt * U
        val contentWidth = setup.contentWidthPt * U
        val contentHeight = setup.contentHeightPt * U
    }

    /** Geometry of the section whose blocks are currently being measured. */
    private var measuring = PageGeometry(doc.sections.firstOrNull()?.page ?: doc.page)

    private val fillPaint = Paint().apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val typefaces = HashMap<Pair<DocxFontKind, Int>, Typeface>()
    private val runStyles = HashMap<DocxRunProps, RunStyle>()
    private val images = ImageStore()

    /**
     * Draws the whole document into [pdf] (at least one page). On failure every started page is
     * finished, so the caller can always close [pdf]; the original exception propagates.
     */
    fun render(pdf: PdfDocument) {
        val first = measuring
        val flows = sequence {
            val sections = doc.sections
            var section = 0
            for ((index, block) in doc.blocks.withIndex()) {
                checkpoint()
                while (section + 1 < sections.size && sections[section + 1].firstBlock <= index) section++
                switchMeasuring(sections.getOrNull(section)?.page ?: doc.page)?.let { yield(it) }
                yieldAll(flowsFor(block, measuring.contentWidth, body = true, depth = 0))
            }
            if (doc.notes.isNotEmpty()) {
                switchMeasuring(doc.page)?.let { yield(it) }
                yieldAll(flowsFor(notesHeading(), measuring.contentWidth, body = true, depth = 0))
                for (note in doc.notes) {
                    for (block in note.blocks) {
                        checkpoint()
                        yieldAll(flowsFor(block, measuring.contentWidth, body = true, depth = 0))
                    }
                }
            }
        }
        val paginator = Paginator(pdf, first)
        try {
            paginator.run(flows)
        } catch (t: Throwable) {
            paginator.abort()
            throw t
        }
    }

    /** Starts measuring for [setup]; returns the flow that tells the paginator, or null if unchanged. */
    private fun switchMeasuring(setup: DocxPageSetup): SectionFlow? {
        if (setup == measuring.setup) return null
        measuring = PageGeometry(setup)
        return SectionFlow(measuring)
    }

    private fun notesHeading(): DocxParagraph {
        val run = DocxRunProps(bold = true, sizePt = NOTES_HEADING_PT)
        val props = DocxParagraphProps(spaceBeforePt = NOTES_SPACE_BEFORE_PT, spaceAfterPt = NOTES_SPACE_AFTER_PT, keepNext = true)
        return DocxParagraph(props, run, null, listOf(DocxInline.Text("Notes", run)))
    }

    // ------------------------------------------------------------------ flow items

    private sealed interface Flow

    /** An explicit page or section break. */
    private object PageBreakFlow : Flow

    /**
     * A new section with its own page setup starts here. A different page size or margins always
     * start a new page (even after a continuous break); later flows are measured for [geometry].
     */
    private class SectionFlow(val geometry: PageGeometry) : Flow

    /** One laid-out paragraph (or the part of one between page breaks). */
    private class ParaBox(
        val layout: StaticLayout,
        /** Left edge of [layout] relative to the container's left edge. */
        val x: Float,
        val spaceBefore: Float,
        val spaceAfter: Float,
        val styleId: String?,
        val contextual: Boolean,
        val keepNext: Boolean,
        val pageBreakBefore: Boolean,
        val shading: Int?,
    ) : Flow {
        val lineCount: Int get() = layout.lineCount
        val height: Float get() = layout.height.toFloat()

        /** Top of [line]; `lineTop(lineCount)` is the bottom of the paragraph. */
        fun lineTop(line: Int): Float = layout.getLineTop(line).toFloat()

        /** Draws lines [fromLine, toLine) with the first one's top at ([left], [top]). */
        fun draw(canvas: Canvas, fromLine: Int, toLine: Int, left: Float, top: Float, fill: Paint) {
            val from = lineTop(fromLine)
            val to = lineTop(toLine)
            shading?.let {
                fill.color = it
                canvas.drawRect(left, top, left + layout.width, top + (to - from), fill)
            }
            canvas.save()
            canvas.translate(left, top - from)
            // Vertical clip keeps neighbouring lines off this page; horizontally anything goes.
            canvas.clipRect(-CLIP_SLACK, from, layout.width + CLIP_SLACK, to)
            layout.draw(canvas)
            canvas.restore()
        }
    }

    private class TableBox(
        /** Left edge relative to the container's left edge. */
        val x: Float,
        val rows: List<RowBox>,
        /** Leading rows repeated at the top of continuation pages. */
        val headerCount: Int,
    ) : Flow {
        val height: Float = rows.fold(0f) { acc, r -> acc + r.height }

        /** Header rows plus the first body row: the least that may start the table on a page. */
        val leadInHeight: Float get() = spanHeight(0, headerCount + 1)

        /** Height of [span] rows starting at [row] (a vertically merged cell's extent). */
        fun spanHeight(row: Int, span: Int): Float {
            var h = 0f
            for (r in row until min(rows.size, row + span)) h += rows[r].height
            return h
        }
    }

    private class RowBox(val height: Float, val cells: List<CellBox>)

    private class CellBox(
        val x: Float,
        val width: Float,
        /** Laid-out content; null for the continuation part of a vertically merged cell. */
        val content: Stack?,
        val margins: DocxCellMargins,
        val shading: Int?,
        val vAlign: DocxCellVAlign,
        val top: DocxBorder?,
        val left: DocxBorder?,
        val bottom: DocxBorder?,
        val right: DocxBorder?,
        /** Rows this cell covers from its row downwards (> 1 for vertical merges). */
        val spanRows: Int,
        /** For a continuation: the merge origin's content, drawn when this cell opens a page. */
        val mergeContent: Stack? = null,
        /** For a continuation: height of the merged rows above this one (where [mergeContent] starts). */
        val offsetInMerge: Float = 0f,
    ) {
        val isContinuation: Boolean get() = content == null
    }

    /** A table cell with its grid columns [start, end). */
    private class Placed(val cell: DocxTableCell, val start: Int, val end: Int)

    /** Flow items stacked vertically without pagination (cell content). */
    private class Stack(val items: List<Flow>, val offsets: FloatArray, val height: Float)

    // ------------------------------------------------------------------ measuring

    private fun flowsFor(block: DocxBlock, width: Float, body: Boolean, depth: Int): List<Flow> = when (block) {
        is DocxParagraph -> paragraphFlows(block, width, body).let {
            if (body && block.sectionBreakAfter) it + PageBreakFlow else it
        }
        is DocxTable -> if (depth < MAX_TABLE_DEPTH) listOfNotNull(measureTable(block, width, depth)) else emptyList()
    }

    /** Splits a paragraph at page breaks (in the body; cells ignore them) and lays out each part. */
    private fun paragraphFlows(p: DocxParagraph, width: Float, body: Boolean): List<Flow> {
        val chunks = ArrayList<List<DocxInline>>()
        var current = ArrayList<DocxInline>()
        for (item in p.items) {
            if (item == DocxInline.PageBreak) {
                if (body) {
                    chunks.add(current)
                    current = ArrayList()
                }
            } else {
                current.add(item)
            }
        }
        chunks.add(current)
        val out = ArrayList<Flow>()
        for ((i, items) in chunks.withIndex()) {
            if (i > 0) out.add(PageBreakFlow)
            val label = if (i == 0) p.label else null
            // A page break alone in a paragraph is just a break, not an extra empty line.
            if (items.isEmpty() && label == null && chunks.size > 1) continue
            out.add(measureParagraph(p, items, label, width, isFirst = i == 0, isLast = i == chunks.lastIndex, body = body))
        }
        return out
    }

    private fun measureParagraph(
        p: DocxParagraph,
        items: List<DocxInline>,
        label: DocxListLabel?,
        width: Float,
        isFirst: Boolean,
        isLast: Boolean,
        body: Boolean,
    ): ParaBox {
        val props = p.props
        val left = props.indentLeftPt * U
        val right = max(0f, props.indentRightPt * U)
        val firstStart = left + props.firstLinePt * U
        val baseX = max(0f, min(left, firstStart))
        val layoutWidth = max(MIN_LAYOUT_WIDTH, width - baseX - right)
        val maxMargin = layoutWidth * MAX_MARGIN_FRACTION
        val firstMargin = (firstStart - baseX).coerceIn(0f, maxMargin)
        val restMargin = (left - baseX).coerceIn(0f, maxMargin)

        val text = SpannableStringBuilder()
        val segmentStarts = arrayListOf(0)
        label?.let {
            appendRun(text, it.text, it.run)
            when (it.suffix) {
                DocxLabelSuffix.TAB -> appendRun(text, "\t", it.run)
                DocxLabelSuffix.SPACE -> appendRun(text, " ", it.run)
                DocxLabelSuffix.NOTHING -> Unit
            }
        }
        for (item in items) {
            when (item) {
                is DocxInline.Text -> appendRun(text, if (item.run.caps || item.run.smallCaps) item.text.uppercase() else item.text, item.run)
                is DocxInline.Tab -> appendRun(text, "\t", item.run)
                is DocxInline.NoteRef -> appendRun(text, item.label, item.run)
                is DocxInline.Image -> appendImage(text, item, layoutWidth - max(firstMargin, restMargin))
                DocxInline.LineBreak -> {
                    text.append('\n')
                    segmentStarts.add(text.length)
                }
                DocxInline.PageBreak -> Unit
            }
        }
        // Empty paragraphs (and a trailing line break) still take one line in the mark's font.
        if (text.isEmpty() || text.last() == '\n') appendRun(text, ZERO_WIDTH_SPACE, p.markRun)
        val length = text.length

        // Line breaks start new StaticLayout paragraphs; only the first gets the first-line indent.
        if (firstMargin > 0f || restMargin > 0f) {
            for ((index, start) in segmentStarts.withIndex()) {
                val end = segmentStarts.getOrNull(index + 1) ?: length
                val span = if (index == 0) {
                    LeadingMarginSpan.Standard(firstMargin.roundToInt(), restMargin.roundToInt())
                } else {
                    LeadingMarginSpan.Standard(restMargin.roundToInt())
                }
                text.setSpan(span, start, end, Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            }
        }
        val markStyle = runStyle(p.markRun)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = TEXT_COLOR
            typeface = markStyle.typeface
            textSize = markStyle.sizeU
        }
        if (text.contains('\t')) {
            val stops = tabStops(props, baseX + firstMargin, baseX + restMargin, layoutWidth)
            for (stop in alignTabStops(text, stops, paint, layoutWidth)) {
                text.setSpan(TabStopSpan.Standard(stop.roundToInt()), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
            }
        }
        val lineValue = props.lineValue.takeIf { it.isFinite() }
        if (props.lineRule != DocxLineRule.AUTO && lineValue != null) {
            val heightU = (lineValue * U).coerceIn(1f, MAX_LINE_U).roundToInt()
            text.setSpan(FixedLineHeightSpan(heightU, atLeast = props.lineRule == DocxLineRule.AT_LEAST), 0, length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
        val multiplier = if (props.lineRule == DocxLineRule.AUTO && lineValue != null) lineValue else 1f

        val builder = StaticLayout.Builder.obtain(text, 0, length, paint, layoutWidth.toInt())
            .setAlignment(
                when (props.alignment) {
                    DocxAlignment.CENTER -> Layout.Alignment.ALIGN_CENTER
                    DocxAlignment.RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
                    DocxAlignment.LEFT, DocxAlignment.JUSTIFY -> Layout.Alignment.ALIGN_NORMAL
                },
            )
            .setLineSpacing(0f, multiplier)
            .setIncludePad(false)
            // Word breaks lines greedily and doesn't hyphenate by default.
            .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        if (props.alignment == DocxAlignment.JUSTIFY) builder.setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD)

        return ParaBox(
            layout = builder.build(),
            x = baseX,
            spaceBefore = if (isFirst) props.spaceBeforePt * U else 0f,
            spaceAfter = if (isLast) props.spaceAfterPt * U else 0f,
            styleId = props.styleId,
            contextual = props.contextualSpacing,
            keepNext = isLast && props.keepNext,
            pageBreakBefore = isFirst && body && props.pageBreakBefore,
            shading = props.shading,
        )
    }

    /**
     * Tab stops relative to the first line's start (StaticLayout measures tabs from the start of
     * the line's text). Word measures stops from the column edge and adds an implicit stop at the
     * hanging indent and default stops every half inch after the last custom one.
     */
    private fun tabStops(props: DocxParagraphProps, firstLineStart: Float, restStart: Float, layoutWidth: Float): List<TabStop> {
        val stops = java.util.TreeMap<Float, DocxTabAlign>()
        for (stop in props.tabStops) {
            val rel = stop.posPt * U - firstLineStart
            if (rel > 0f) stops[rel] = stop.align
        }
        if (restStart > firstLineStart) stops.putIfAbsent(restStart - firstLineStart, DocxTabAlign.LEFT)
        val lastCustom = if (stops.isEmpty()) 0f else stops.lastKey()
        val step = DEFAULT_TAB_PT * U
        var k = 1
        while (true) {
            val rel = k * step - firstLineStart
            if (rel >= layoutWidth) break
            if (rel > lastCustom) stops[rel] = DocxTabAlign.LEFT
            k++
        }
        return stops.map { (pos, align) -> TabStop(pos, align) }
    }

    /** A tab stop relative to the first line's text start, in units. */
    private class TabStop(var pos: Float, var align: DocxTabAlign)

    /**
     * StaticLayout only knows left tab stops. Emulates right, centre and decimal stops: for each tab
     * that reaches such a stop, the text after it (up to the next tab or line break) is measured and
     * the stop is replaced by a left stop where that text has to start. Stops apply to the whole
     * paragraph, so each one is resolved by the first tab that reaches it (TOC entries, form lines).
     * Pen positions are estimated per line segment and assume the text before a tab doesn't wrap.
     */
    private fun alignTabStops(text: CharSequence, stops: List<TabStop>, paint: TextPaint, layoutWidth: Float): List<Float> {
        if (stops.any { it.align != DocxTabAlign.LEFT }) {
            var pen = 0f
            var segmentStart = 0
            var guessing = true
            for (i in text.indices) {
                val c = text[i]
                if (c == '\n') {
                    pen = 0f
                    segmentStart = i + 1
                    guessing = true
                    continue
                }
                if (c != '\t' || !guessing) continue
                pen += Layout.getDesiredWidth(text, segmentStart, i, paint)
                val stop = stops.firstOrNull { it.pos > pen }
                if (stop == null || pen >= layoutWidth) {
                    // Past the last stop or wrapped: positions after this are unknowable here.
                    guessing = false
                    continue
                }
                if (stop.align != DocxTabAlign.LEFT) {
                    var end = i + 1
                    while (end < text.length && text[end] != '\t' && text[end] != '\n') end++
                    val measureEnd = if (stop.align == DocxTabAlign.DECIMAL) {
                        (i + 1 until end).firstOrNull { text[it] == '.' } ?: end
                    } else {
                        end
                    }
                    val width = Layout.getDesiredWidth(text, i + 1, measureEnd, paint)
                    val start = if (stop.align == DocxTabAlign.CENTER) stop.pos - width / 2f else stop.pos - width
                    // Text that doesn't fit before the stop starts right after the pen, like Word.
                    stop.pos = max(pen + MIN_TAB_ADVANCE, start)
                    stop.align = DocxTabAlign.LEFT
                }
                pen = stop.pos
                segmentStart = i + 1
            }
        }
        return stops.map { it.pos }.distinct().sorted()
    }

    private fun appendRun(text: SpannableStringBuilder, s: String, run: DocxRunProps) {
        if (s.isEmpty()) return
        val start = text.length
        text.append(s)
        text.setSpan(RunSpan(runStyle(run)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun appendImage(text: SpannableStringBuilder, image: DocxInline.Image, maxWidth: Float) {
        var w = image.widthPt
        var h = image.heightPt
        if (w <= 0f || h <= 0f) {
            // The intrinsic size is the whole picture's; the box shows only the cropped part.
            val intrinsic = images.intrinsicSizePt(image.entryName)
            w = intrinsic?.first?.times(1f - image.cropLeft - image.cropRight) ?: PLACEHOLDER_WIDTH_PT
            h = intrinsic?.second?.times(1f - image.cropTop - image.cropBottom) ?: PLACEHOLDER_HEIGHT_PT
        }
        // Fit the column width and the page height (Word shrinks oversized pictures the same way).
        val scale = min(1f, min(maxWidth / (w * U), measuring.contentHeight * MAX_IMAGE_PAGE_FRACTION / (h * U)))
        val widthU = (w * U * scale).roundToInt().coerceAtLeast(1)
        val heightU = (h * U * scale).roundToInt().coerceAtLeast(1)
        val start = text.length
        text.append(OBJECT_REPLACEMENT)
        text.setSpan(ImageSpan(image, widthU, heightU), start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }

    private fun measureTable(t: DocxTable, available: Float, depth: Int): TableBox? {
        val rows = t.rows.filter { it.cells.isNotEmpty() }
        if (rows.isEmpty()) return null
        val colCount = max(t.gridPt.size, rows.maxOf { r -> r.gridBefore + r.cells.sumOf { it.gridSpan } })
            .coerceIn(1, MAX_COLUMNS)

        // Column widths: tblGrid, missing columns get the average, everything shrinks to fit.
        val known = t.gridPt.filter { it > 0f }
        val fallbackPt = if (known.isNotEmpty()) known.average().toFloat() else available / U / colCount
        val widths = FloatArray(colCount) { i -> (t.gridPt.getOrNull(i)?.takeIf { it > 0f } ?: fallbackPt) * U }
        val indent = if (t.alignment == DocxAlignment.LEFT) max(0f, t.indentPt * U) else 0f
        val maxWidth = max(MIN_LAYOUT_WIDTH, available - indent)
        val total = widths.sum()
        if (total > maxWidth) for (i in widths.indices) widths[i] *= maxWidth / total
        val colX = FloatArray(colCount + 1)
        for (i in 0 until colCount) colX[i + 1] = colX[i] + widths[i]
        val tableWidth = colX[colCount]
        val x = when (t.alignment) {
            DocxAlignment.CENTER -> max(0f, (available - tableWidth) / 2f)
            DocxAlignment.RIGHT -> max(0f, available - tableWidth)
            DocxAlignment.LEFT, DocxAlignment.JUSTIFY -> indent
        }

        val placed = rows.map { row ->
            var col = row.gridBefore.coerceAtMost(colCount - 1)
            row.cells.mapNotNull { cell ->
                if (col >= colCount) return@mapNotNull null
                val end = min(colCount, col + cell.gridSpan)
                Placed(cell, col, end).also { col = end }
            }
        }

        fun cellAt(row: Int, start: Int): Placed? = placed.getOrNull(row)?.firstOrNull { it.start == start }
        fun isContinuation(row: Int, pc: Placed): Boolean =
            pc.cell.vMerge == DocxVMerge.CONTINUE && row > 0 && cellAt(row - 1, pc.start) != null

        /** Number of consecutive continuation cells in column [start] from row [from] down. */
        fun continuationsFrom(from: Int, start: Int): Int {
            var r = from
            while (r < rows.size) {
                val below = cellAt(r, start) ?: break
                if (!isContinuation(r, below)) break
                r++
            }
            return r - from
        }

        /** Rows covered by the cell at ([row], [start]), counting from [row]. */
        fun mergedRows(row: Int, start: Int, continuation: Boolean): Int =
            if (continuation) max(1, continuationsFrom(row, start)) else 1 + continuationsFrom(row + 1, start)

        /** Row of the cell that starts the vertical merge containing the continuation at ([row], [start]). */
        fun mergeOriginRow(row: Int, pc: Placed): Int {
            var r = row
            while (r > 0) {
                val cell = cellAt(r, pc.start) ?: break
                if (!isContinuation(r, cell)) break
                r--
            }
            return r
        }

        val m = t.cellMargins
        val padTop = m.topPt * U
        val padBottom = m.bottomPt * U
        val contents = placed.mapIndexed { r, cells ->
            checkpoint()
            cells.map { pc ->
                if (isContinuation(r, pc)) {
                    null
                } else {
                    val inner = max(MIN_CELL_CONTENT, colX[pc.end] - colX[pc.start] - (m.leftPt + m.rightPt) * U)
                    stack(pc.cell.blocks.flatMap { flowsFor(it, inner, body = false, depth = depth + 1) })
                }
            }
        }

        // Row heights: tallest single-row cell, trHeight rules, then grow the last row of each vertical
        // merge so the merged cell's content fits.
        val heights = FloatArray(rows.size) { r ->
            var h = padTop + padBottom
            placed[r].forEachIndexed { i, pc ->
                val content = contents[r][i]
                if (content != null && mergedRows(r, pc.start, continuation = false) == 1) {
                    h = max(h, padTop + content.height + padBottom)
                }
            }
            val spec = rows[r].heightPt * U
            if (rows[r].exactHeight && spec > 0f) spec else max(h, spec)
        }
        placed.forEachIndexed { r, cells ->
            cells.forEachIndexed { i, pc ->
                val content = contents[r][i] ?: return@forEachIndexed
                val span = mergedRows(r, pc.start, continuation = false)
                if (span > 1) {
                    val last = r + span - 1
                    var have = 0f
                    for (k in r..last) have += heights[k]
                    val need = padTop + content.height + padBottom
                    if (need > have && !rows[last].exactHeight) heights[last] += need - have
                }
            }
        }

        val tb = t.borders
        val rowBoxes = placed.mapIndexed { r, cells ->
            RowBox(
                heights[r],
                cells.mapIndexed { i, pc ->
                    val continuation = isContinuation(r, pc)
                    val span = mergedRows(r, pc.start, continuation)
                    val lastRow = r + span - 1
                    val lastCell = cellAt(lastRow, pc.start)?.cell ?: pc.cell
                    val originRow = if (continuation) mergeOriginRow(r, pc) else r
                    val originIndex = placed[originRow].indexOfFirst { it.start == pc.start }
                    val origin = placed[originRow].getOrNull(originIndex)?.cell ?: pc.cell
                    var offsetInMerge = 0f
                    for (k in originRow until r) offsetInMerge += heights[k]
                    CellBox(
                        x = colX[pc.start],
                        width = colX[pc.end] - colX[pc.start],
                        content = contents[r][i],
                        margins = m,
                        shading = pc.cell.shading ?: origin.shading,
                        vAlign = origin.vAlign,
                        top = pc.cell.borders.top ?: if (r == 0) tb.top else tb.insideH,
                        left = pc.cell.borders.left ?: if (pc.start == 0) tb.left else tb.insideV,
                        bottom = lastCell.borders.bottom ?: if (lastRow == rows.lastIndex) tb.bottom else tb.insideH,
                        right = pc.cell.borders.right ?: if (pc.end >= colCount) tb.right else tb.insideV,
                        spanRows = span,
                        mergeContent = if (continuation) contents[originRow].getOrNull(originIndex) else null,
                        offsetInMerge = offsetInMerge,
                    )
                },
            )
        }
        val headers = rows.takeWhile { it.isHeader }.size
        return TableBox(x, rowBoxes, headerCount = if (headers == rows.size) 0 else headers)
    }

    private fun stack(flows: List<Flow>): Stack {
        val offsets = FloatArray(flows.size)
        var y = 0f
        var prev: ParaBox? = null
        var after = 0f
        flows.forEachIndexed { i, f ->
            when (f) {
                is ParaBox -> {
                    y += gapBetween(prev, after, f)
                    offsets[i] = y
                    y += f.height
                    prev = f
                    after = f.spaceAfter
                }
                is TableBox -> {
                    y += after
                    offsets[i] = y
                    y += f.height
                    prev = null
                    after = 0f
                }
                PageBreakFlow, is SectionFlow -> offsets[i] = y
            }
        }
        return Stack(flows, offsets, y + after)
    }

    /**
     * Vertical gap between paragraphs: space after the previous plus space before this one (Word
     * adds them), except that contextual spacing drops both between paragraphs of the same style.
     */
    private fun gapBetween(prev: ParaBox?, prevAfter: Float, cur: ParaBox): Float {
        val sameStyle = prev != null && prev.styleId == cur.styleId
        val before = if (cur.contextual && sameStyle) 0f else cur.spaceBefore
        val after = if (prev != null && prev.contextual && sameStyle) 0f else prevAfter
        return before + after
    }

    // ------------------------------------------------------------------ drawing helpers

    private fun drawStack(canvas: Canvas, stack: Stack, left: Float, top: Float) {
        stack.items.forEachIndexed { i, item ->
            val y = top + stack.offsets[i]
            when (item) {
                is ParaBox -> item.draw(canvas, 0, item.lineCount, left + item.x, y, fillPaint)
                is TableBox -> {
                    var rowTop = y
                    for (r in item.rows.indices) {
                        drawRow(canvas, item, r, left + item.x, rowTop, startsPage = r == 0)
                        rowTop += item.rows[r].height
                    }
                }
                PageBreakFlow, is SectionFlow -> Unit
            }
        }
    }

    /**
     * Draws row [rowIndex] of [table] with its top-left at ([left], [top]). A vertically merged cell
     * is painted by its first row over the merged rows on the same page; when the merge continues on
     * a later page, the continuation cell that opens that page ([startsPage]) paints the rest,
     * including the part of the origin's content that falls into it. [clipBottom] is where the rows
     * placed on this page end: merged cells never paint below it.
     */
    private fun drawRow(
        canvas: Canvas,
        table: TableBox,
        rowIndex: Int,
        left: Float,
        top: Float,
        startsPage: Boolean,
        clipBottom: Float = Float.POSITIVE_INFINITY,
    ) {
        val row = table.rows[rowIndex]
        for (cell in row.cells) {
            if (cell.isContinuation && !startsPage) continue
            val h = table.spanHeight(rowIndex, cell.spanRows)
            val bottom = cellBottom(cell, top, h, row.height, clipBottom)
            val cx = left + cell.x
            cell.shading?.let {
                fillPaint.color = it
                canvas.drawRect(cx, top, cx + cell.width, bottom, fillPaint)
            }
            val content = cell.content ?: cell.mergeContent ?: continue
            // A continuation shows the origin's content shifted up by the merged rows above it.
            val shift = if (cell.content != null) 0f else cell.offsetInMerge
            val padTop = cell.margins.topPt * U
            val inner = shift + h - padTop - cell.margins.bottomPt * U
            val offset = when (cell.vAlign) {
                DocxCellVAlign.TOP -> 0f
                DocxCellVAlign.CENTER -> max(0f, (inner - content.height) / 2f)
                DocxCellVAlign.BOTTOM -> max(0f, inner - content.height)
            }
            canvas.save()
            canvas.clipRect(cx, top, cx + cell.width, bottom)
            drawStack(canvas, content, cx + cell.margins.leftPt * U, top - shift + padTop + offset)
            canvas.restore()
        }
        for (cell in row.cells) {
            if (cell.isContinuation && !startsPage) continue
            val bottom = cellBottom(cell, top, table.spanHeight(rowIndex, cell.spanRows), row.height, clipBottom)
            val cx = left + cell.x
            drawBorder(canvas, cell.top, cx, top, cx + cell.width, top)
            drawBorder(canvas, cell.bottom, cx, bottom, cx + cell.width, bottom)
            drawBorder(canvas, cell.left, cx, top, cx, bottom)
            drawBorder(canvas, cell.right, cx + cell.width, top, cx + cell.width, bottom)
        }
    }

    /** Bottom edge of [cell] (spanning [h] from [top]) on this page: merges stop at [clipBottom]. */
    private fun cellBottom(cell: CellBox, top: Float, h: Float, rowHeight: Float, clipBottom: Float): Float =
        if (cell.spanRows > 1) max(top + rowHeight, min(top + h, clipBottom)) else top + h

    private fun drawBorder(canvas: Canvas, border: DocxBorder?, x0: Float, y0: Float, x1: Float, y1: Float) {
        if (border == null || !border.isVisible) return
        strokePaint.color = border.color
        strokePaint.strokeWidth = border.widthPt * U
        canvas.drawLine(x0, y0, x1, y1, strokePaint)
    }

    // ------------------------------------------------------------------ pagination

    private inner class Paginator(private val pdf: PdfDocument, private var geo: PageGeometry) {
        private var page: PdfDocument.Page? = null
        private var pageCount = 0

        /** Cursor below the content top of the current page, in units. */
        private var y = 0f
        private var prevPara: ParaBox? = null
        private var pendingAfter = 0f
        private val atTop: Boolean get() = y <= 0f
        private val contentHeight: Float get() = geo.contentHeight

        fun run(flows: Sequence<Flow>) {
            val it = flows.iterator()
            var current = if (it.hasNext()) it.next() else null
            while (current != null) {
                val next = if (it.hasNext()) it.next() else null
                when (current) {
                    is ParaBox -> placeParagraph(current, next)
                    is TableBox -> placeTable(current)
                    PageBreakFlow -> explicitBreak()
                    is SectionFlow -> startSection(current.geometry)
                }
                current = next
            }
            page?.let { pdf.finishPage(it) }
            page = null
            // A PDF needs at least one page; an empty document gets a blank one.
            if (pageCount == 0) pdf.finishPage(startPage())
        }

        /**
         * Finishes the open page, if any, after a failure. PdfDocument.close() refuses to run while a
         * page is open, which would mask the real error and leak the native document.
         */
        fun abort() {
            val p = page ?: return
            page = null
            runCatching { pdf.finishPage(p) }
        }

        private fun canvas(): Canvas = (page ?: startPage()).canvas

        private fun startPage(): PdfDocument.Page {
            checkpoint()
            if (pageCount >= MAX_PAGES) throw DocxConversionException(TOO_LONG_MESSAGE)
            val p = pdf.startPage(PdfDocument.PageInfo.Builder(geo.widthPt, geo.heightPt, ++pageCount).create())
            p.canvas.scale(1f / U, 1f / U)
            p.canvas.translate(geo.originX, geo.originY)
            page = p
            return p
        }

        /** Moves to a fresh page; the page itself is created lazily by the next drawing call. */
        private fun nextPage() {
            page?.let { pdf.finishPage(it) }
            page = null
            y = 0f
            prevPara = null
            pendingAfter = 0f
        }

        /** A hard break. At the very start it yields a blank first page, like Word; at a fresh page it's a no-op. */
        private fun explicitBreak() {
            if (page == null && pageCount == 0) startPage()
            if (page != null) nextPage()
        }

        /** A section with a different page setup can't share a page with the previous one. */
        private fun startSection(geometry: PageGeometry) {
            if (geometry.setup == geo.setup) return
            if (page != null) nextPage()
            geo = geometry
        }

        private fun leadHeight(flow: Flow?): Float = when (flow) {
            is ParaBox -> flow.spaceBefore + flow.lineTop(min(1, flow.lineCount))
            is TableBox -> flow.leadInHeight
            PageBreakFlow, is SectionFlow, null -> 0f
        }

        private fun placeParagraph(p: ParaBox, next: Flow?) {
            if (p.pageBreakBefore && !atTop) nextPage()
            var gap = if (atTop) 0f else gapBetween(prevPara, pendingAfter, p)
            if (p.keepNext && !atTop) {
                val lead = leadHeight(next)
                if (y + gap + p.height + lead > contentHeight && p.height + lead <= contentHeight) {
                    nextPage()
                    gap = 0f
                }
            }
            val n = p.lineCount
            var from = 0
            while (from < n) {
                val available = contentHeight - y - gap
                var to = from
                while (to < n && p.lineTop(to + 1) - p.lineTop(from) <= available) to++
                if (to < n && !atTop) {
                    // Nothing fits, or only the first line would (an orphan): continue on the next page.
                    val orphan = from == 0 && to == 1 && n > 1
                    if (to == from || orphan) {
                        nextPage()
                        gap = 0f
                        continue
                    }
                }
                if (to == from) to = from + 1 // a single line taller than the page: draw it clipped
                if (to < n && n - to == 1 && to - from >= 2) to-- // no widow: carry a second line over
                y += gap
                gap = 0f
                p.draw(canvas(), from, to, p.x, y, fillPaint)
                y += p.lineTop(to) - p.lineTop(from)
                from = to
                if (from < n) nextPage()
            }
            prevPara = p
            pendingAfter = p.spaceAfter
        }

        private fun placeTable(t: TableBox) {
            if (!atTop) y += pendingAfter
            prevPara = null
            pendingAfter = 0f
            // Header rows never sit alone at the bottom of a page: keep them with the first body row.
            if (!atTop && y + t.leadInHeight > contentHeight && t.leadInHeight <= contentHeight) nextPage()
            // The first body row on a page also paints the parts of vertically merged cells that
            // continue from the previous page (repeated header rows don't count).
            var firstOnPage = atTop
            for (i in t.rows.indices) {
                checkpoint()
                val h = t.rows[i].height
                if (y + h > contentHeight && !atTop && h <= contentHeight) {
                    nextPage()
                    firstOnPage = true
                }
                if (atTop && i > 0 && i >= t.headerCount && t.headerCount > 0) {
                    val headerHeight = t.spanHeight(0, t.headerCount)
                    if (headerHeight + h <= contentHeight) {
                        for (r in 0 until t.headerCount) {
                            drawClippedToPage { drawRow(it, t, r, t.x, y, startsPage = r == 0) }
                            y += t.rows[r].height
                        }
                    }
                }
                if (y + h <= contentHeight) {
                    val clipBottom = rowsBottom(t, i, y)
                    drawClippedToPage { drawRow(it, t, i, t.x, y, firstOnPage, clipBottom) }
                    y += h
                } else {
                    sliceRow(t, i)
                }
                firstOnPage = false
            }
        }

        /** Bottom of the consecutive rows from [from] on that fit on this page when [from] starts at [top]. */
        private fun rowsBottom(t: TableBox, from: Int, top: Float): Float {
            var bottom = top
            var r = from
            while (r < t.rows.size && bottom + t.rows[r].height <= contentHeight) {
                bottom += t.rows[r].height
                r++
            }
            return bottom
        }

        /** Draws a row taller than the remaining space (and than a page) across several pages. */
        private fun sliceRow(t: TableBox, i: Int) {
            val h = t.rows[i].height
            if (!atTop && contentHeight - y < contentHeight * MIN_SLICE_FRACTION) nextPage()
            var offset = 0f
            while (true) {
                val available = contentHeight - y
                val drawn = min(available, h - offset)
                val last = offset + drawn >= h - SLICE_EPSILON
                // On the last slice, merged cells may continue into the rows that follow on this page.
                val clipBottom = if (last) rowsBottom(t, i + 1, y + drawn) else contentHeight
                val c = canvas()
                c.save()
                c.clipRect(-geo.originX, y, geo.widthPt * U, contentHeight)
                drawRow(c, t, i, t.x, y - offset, startsPage = true, clipBottom = clipBottom)
                c.restore()
                offset += drawn
                y += drawn
                if (last) break
                nextPage()
            }
        }

        /** Keeps merged cells that continue on the next page out of the bottom margin. */
        private inline fun drawClippedToPage(draw: (Canvas) -> Unit) {
            val c = canvas()
            c.save()
            c.clipRect(-geo.originX, -geo.originY, geo.widthPt * U, contentHeight + BORDER_ALLOWANCE)
            draw(c)
            c.restore()
        }
    }

    // ------------------------------------------------------------------ text styling

    private class RunStyle(
        val typeface: Typeface,
        val fakeBold: Boolean,
        val fakeItalic: Boolean,
        val sizeU: Float,
        val baselineShiftU: Int,
        val color: Int,
        val underline: Boolean,
        val strike: Boolean,
        val background: Int,
    )

    private fun runStyle(run: DocxRunProps): RunStyle = runStyles.getOrPut(run) {
        val typeface = typeface(DocxFontKind.of(run.fontName), run.bold, run.italic)
        val baseSize = run.sizePt * U * (if (run.smallCaps) SMALL_CAPS_SCALE else 1f)
        RunStyle(
            typeface = typeface,
            fakeBold = run.bold && !typeface.isBold,
            fakeItalic = run.italic && !typeface.isItalic,
            sizeU = if (run.vertAlign == DocxVertAlign.BASELINE) baseSize else baseSize * SCRIPT_SCALE,
            baselineShiftU = when (run.vertAlign) {
                DocxVertAlign.SUPERSCRIPT -> -(baseSize * SUPERSCRIPT_RISE).roundToInt()
                DocxVertAlign.SUBSCRIPT -> (baseSize * SUBSCRIPT_DROP).roundToInt()
                DocxVertAlign.BASELINE -> 0
            },
            color = run.color ?: TEXT_COLOR,
            underline = run.underline,
            strike = run.strike,
            background = run.highlight ?: 0,
        )
    }

    private fun typeface(kind: DocxFontKind, bold: Boolean, italic: Boolean): Typeface {
        val style = (if (bold) Typeface.BOLD else 0) or (if (italic) Typeface.ITALIC else 0)
        return typefaces.getOrPut(kind to style) {
            val family = when (kind) {
                DocxFontKind.SANS -> Typeface.SANS_SERIF
                DocxFontKind.SERIF -> Typeface.SERIF
                DocxFontKind.MONO -> Typeface.MONOSPACE
            }
            Typeface.create(family, style)
        }
    }

    /**
     * All character formatting of one run in a single span: family + style (with synthetic bold or
     * italic when the font lacks them), size, super/subscript shift, colour, decorations, highlight.
     * Works on every API level, unlike TypefaceSpan(Typeface) which needs API 28.
     */
    private class RunSpan(private val style: RunStyle) : MetricAffectingSpan() {
        override fun updateMeasureState(textPaint: TextPaint) = applyMetrics(textPaint)

        override fun updateDrawState(tp: TextPaint) {
            applyMetrics(tp)
            tp.color = style.color
            tp.isUnderlineText = style.underline
            tp.isStrikeThruText = style.strike
            tp.bgColor = style.background
        }

        private fun applyMetrics(tp: TextPaint) {
            tp.typeface = style.typeface
            tp.isFakeBoldText = style.fakeBold
            tp.textSkewX = if (style.fakeItalic) FAKE_ITALIC_SKEW else 0f
            tp.textSize = style.sizeU
            tp.baselineShift += style.baselineShiftU
        }
    }

    /** Exact or minimum line height ("exactly"/"at least" spacing), keeping the baseline proportion. */
    private class FixedLineHeightSpan(private val height: Int, private val atLeast: Boolean) : LineHeightSpan {
        override fun chooseHeight(text: CharSequence, start: Int, end: Int, spanstartv: Int, lineHeight: Int, fm: Paint.FontMetricsInt) {
            val current = fm.descent - fm.ascent
            if (current <= 0 || (atLeast && current >= height)) return
            val descent = (fm.descent.toLong() * height / current).toInt()
            fm.descent = descent
            fm.ascent = descent - height
            fm.bottom = fm.descent
            fm.top = fm.ascent
        }
    }

    /** An inline picture sitting on the baseline; decoded only when drawn. */
    private inner class ImageSpan(private val image: DocxInline.Image, private val widthU: Int, private val heightU: Int) : ReplacementSpan() {
        override fun getSize(paint: Paint, text: CharSequence?, start: Int, end: Int, fm: Paint.FontMetricsInt?): Int {
            if (fm != null) {
                fm.ascent = -heightU
                fm.descent = 0
                fm.top = fm.ascent
                fm.bottom = 0
            }
            return widthU
        }

        override fun draw(canvas: Canvas, text: CharSequence?, start: Int, end: Int, x: Float, top: Int, y: Int, bottom: Int, paint: Paint) {
            val dst = RectF(x, (y - heightU).toFloat(), x + widthU, y.toFloat())
            // Negative crops are padding: the picture fills only part of its box.
            val spanW = 1f - image.cropLeft - image.cropRight
            val spanH = 1f - image.cropTop - image.cropBottom
            val picture = RectF(
                dst.left + max(0f, -image.cropLeft) / spanW * dst.width(),
                dst.top + max(0f, -image.cropTop) / spanH * dst.height(),
                dst.right - max(0f, -image.cropRight) / spanW * dst.width(),
                dst.bottom - max(0f, -image.cropBottom) / spanH * dst.height(),
            )
            val bitmap = if (picture.width() >= 1f && picture.height() >= 1f) {
                images.bitmap(image, picture.width() / U, picture.height() / U)
            } else {
                null
            }
            if (bitmap != null) {
                canvas.drawBitmap(bitmap, null, picture, bitmapPaint)
            } else {
                // Unsupported format (EMF/WMF/SVG) or unreadable: keep the space with a quiet box.
                fillPaint.color = PLACEHOLDER_FILL
                canvas.drawRect(dst, fillPaint)
                strokePaint.color = PLACEHOLDER_STROKE
                strokePaint.strokeWidth = U * 0.5f
                canvas.drawRect(dst, strokePaint)
            }
        }
    }

    /** One decoded picture: entry, display size (0.1 pt) and the cropped-in edges. */
    private data class ImageKey(val entryName: String, val widthDpt: Int, val heightDpt: Int, val crop: List<Float>)

    /**
     * Decodes pictures at roughly the resolution they're shown at (~180 dpi, never more than
     * [MAX_IMAGE_PX] on the longest side) so big photos don't exhaust memory. PdfDocument keeps every
     * drawn bitmap until it's written, so the cache costs nothing extra and dedupes repeated images;
     * instead, decoded memory per conversion is budgeted: past [SOFT_IMAGE_BUDGET_BYTES] later
     * pictures get fewer pixels (and opaque JPEGs 16-bit colour), past [HARD_IMAGE_BUDGET_BYTES] the
     * conversion stops with a "too large" message rather than risking a native out-of-memory kill.
     *
     * Cropped pictures are cut to their visible part before PdfDocument sees them, so cropped-out
     * pixels never end up in the PDF. Failures are cached: an undecodable picture (EMF, TIFF, ...)
     * is read and probed once, not on every draw.
     */
    private inner class ImageStore {
        private val bitmaps = HashMap<ImageKey, Bitmap?>()
        private val intrinsicSizes = HashMap<String, Pair<Float, Float>?>()
        private val failedEntries = HashSet<String>()
        private var decodedBytes = 0L

        fun intrinsicSizePt(entryName: String): Pair<Float, Float>? {
            if (intrinsicSizes.containsKey(entryName)) return intrinsicSizes[entryName]
            val size = bounds(entryName)?.let { opts -> opts.outWidth * PT_PER_PX to opts.outHeight * PT_PER_PX }
            intrinsicSizes[entryName] = size
            return size
        }

        fun bitmap(image: DocxInline.Image, widthPt: Float, heightPt: Float): Bitmap? {
            val crop = listOf(image.cropLeft, image.cropTop, image.cropRight, image.cropBottom).map { max(0f, it) }
            val key = ImageKey(image.entryName, (widthPt * 10f).roundToInt(), (heightPt * 10f).roundToInt(), crop)
            if (bitmaps.containsKey(key)) return bitmaps[key]
            if (decodedBytes > HARD_IMAGE_BUDGET_BYTES) throw DocxConversionException(DocxConverter.TOO_LARGE_MESSAGE)
            val longestPt = max(widthPt, heightPt)
            val factor = if (decodedBytes <= SOFT_IMAGE_BUDGET_BYTES) 1f else sqrt(SOFT_IMAGE_BUDGET_BYTES.toFloat() / decodedBytes)
            val target = min(MAX_IMAGE_PX, ceil(longestPt * max(MIN_IMAGE_PX_PER_PT, IMAGE_PX_PER_PT * factor)).toInt()).coerceAtLeast(1)
            val bitmap = decode(image.entryName, target, crop, lowMemory = factor < 1f)
            bitmap?.let { decodedBytes += it.allocationByteCount }
            bitmaps[key] = bitmap
            return bitmap
        }

        private fun bounds(entryName: String): BitmapFactory.Options? {
            if (entryName in failedEntries) return null
            val bytes = bytes(entryName)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (bytes == null || opts.outWidth <= 0 || opts.outHeight <= 0) {
                failedEntries.add(entryName)
                return null
            }
            return opts
        }

        private fun decode(entryName: String, target: Int, crop: List<Float>, lowMemory: Boolean): Bitmap? = try {
            val bytes = if (entryName in failedEntries) null else bytes(entryName)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (bytes != null) BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bytes == null || bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                failedEntries.add(entryName)
                null
            } else {
                val w = bounds.outWidth
                val h = bounds.outHeight
                val region = Rect(
                    (crop[0] * w).roundToInt().coerceIn(0, w - 1),
                    (crop[1] * h).roundToInt().coerceIn(0, h - 1),
                    (w - crop[2] * w).roundToInt().coerceIn(1, w),
                    (h - crop[3] * h).roundToInt().coerceIn(1, h),
                )
                if (region.width() <= 0 || region.height() <= 0) region.set(0, 0, w, h)
                val longestSource = max(region.width(), region.height())
                var sample = 1
                while (longestSource / (sample * 2) >= target) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    // JPEGs have no alpha: 16-bit colour halves their memory once the budget is tight.
                    if (lowMemory && bounds.outMimeType == "image/jpeg") inPreferredConfig = Bitmap.Config.RGB_565
                }
                val decoded = if (region.width() == w && region.height() == h) {
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                } else {
                    decodeRegion(bytes, region, opts) ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.let { cut(it, region, w, h) }
                }
                decoded?.let { downscale(it, target) }
            }
        } catch (_: OutOfMemoryError) {
            null
        } catch (_: RuntimeException) {
            null
        }

        /** Decodes only [region] of the picture; null when the format doesn't support it (GIF, BMP). */
        private fun decodeRegion(bytes: ByteArray, region: Rect, opts: BitmapFactory.Options): Bitmap? {
            val decoder: BitmapRegionDecoder? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                } else {
                    @Suppress("DEPRECATION")
                    BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
                }
            } catch (_: IOException) {
                null
            }
            if (decoder == null) return null
            return try {
                decoder.decodeRegion(region, opts)
            } finally {
                decoder.recycle()
            }
        }

        /** Cuts [region] (in source pixels of a [sourceW] x [sourceH] picture) out of a decoded [full] picture. */
        private fun cut(full: Bitmap, region: Rect, sourceW: Int, sourceH: Int): Bitmap {
            val sx = full.width / sourceW.toFloat()
            val sy = full.height / sourceH.toFloat()
            val x = (region.left * sx).roundToInt().coerceIn(0, full.width - 1)
            val y = (region.top * sy).roundToInt().coerceIn(0, full.height - 1)
            val cw = (region.width() * sx).roundToInt().coerceIn(1, full.width - x)
            val ch = (region.height() * sy).roundToInt().coerceIn(1, full.height - y)
            val cropped = Bitmap.createBitmap(full, x, y, cw, ch)
            if (cropped !== full) full.recycle()
            return cropped
        }

        private fun downscale(bitmap: Bitmap, target: Int): Bitmap {
            val longest = max(bitmap.width, bitmap.height)
            if (longest <= target * DOWNSCALE_TOLERANCE) return bitmap
            val f = target / longest.toFloat()
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * f).roundToInt().coerceAtLeast(1),
                (bitmap.height * f).roundToInt().coerceAtLeast(1),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            return scaled
        }

        private fun bytes(entryName: String): ByteArray? = try {
            loadImage(entryName)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Layout units per PDF point. */
        private const val U = 4f

        const val TOO_LONG_MESSAGE = "This document is too long to open."
        private const val MAX_PAGES = 5000
        private const val MAX_COLUMNS = 63
        private const val MAX_TABLE_DEPTH = 8

        private const val TEXT_COLOR = 0xFF000000.toInt()
        private const val PLACEHOLDER_FILL = 0xFFEEEEEE.toInt()
        private const val PLACEHOLDER_STROKE = 0xFFBDBDBD.toInt()
        private const val ZERO_WIDTH_SPACE = "​"
        private const val OBJECT_REPLACEMENT = "￼"

        private const val MIN_LAYOUT_WIDTH = 12f * U
        private const val MIN_CELL_CONTENT = 4f * U
        private const val MAX_MARGIN_FRACTION = 0.9f
        private const val CLIP_SLACK = 100_000f
        private const val BORDER_ALLOWANCE = 2f * U
        private const val MIN_SLICE_FRACTION = 0.2f
        private const val SLICE_EPSILON = 0.5f

        private const val DEFAULT_TAB_PT = 36f
        private const val MIN_TAB_ADVANCE = 1f

        /** Largest exact line height (22 inches, Word's largest page), keeps StaticLayout's int metrics sane. */
        private const val MAX_LINE_U = 1584f * U
        private const val SCRIPT_SCALE = 0.7f
        private const val SUPERSCRIPT_RISE = 0.33f
        private const val SUBSCRIPT_DROP = 0.14f
        private const val SMALL_CAPS_SCALE = 0.82f
        private const val FAKE_ITALIC_SKEW = -0.25f

        private const val NOTES_HEADING_PT = 13f
        private const val NOTES_SPACE_BEFORE_PT = 18f
        private const val NOTES_SPACE_AFTER_PT = 6f

        private const val MAX_IMAGE_PX = 1600
        private const val IMAGE_PX_PER_PT = 2.5f
        private const val MIN_IMAGE_PX_PER_PT = 1f
        private const val SOFT_IMAGE_BUDGET_BYTES = 64L * 1024 * 1024
        private const val HARD_IMAGE_BUDGET_BYTES = 384L * 1024 * 1024
        private const val DOWNSCALE_TOLERANCE = 1.25f
        private const val PT_PER_PX = 0.75f
        private const val MAX_IMAGE_PAGE_FRACTION = 0.95f
        private const val PLACEHOLDER_WIDTH_PT = 144f
        private const val PLACEHOLDER_HEIGHT_PT = 108f
    }
}
