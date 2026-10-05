package com.studynotes.app.export

import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studynotes.app.editor.canvas.AnnotationPainter
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Palette
import com.studynotes.app.model.Stroke
import com.studynotes.app.testutil.Fixtures
import com.studynotes.app.testutil.PagePixels
import com.studynotes.app.testutil.PlatformPdf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Proves that exported marks land where the user drew them, on every page type of
 * "Biology Lecture Notes.pdf": plain A4 (page 1), /Rotate 90 (page 5, displayed 842 x 595), crop
 * box offset (page 6, displayed 515 x 762) and Letter landscape (page 7).
 *
 * Each annotated page gets a highlighter and a pen stroke (and some a note) at page-space positions
 * on blank paper. The export is opened with the plain platform android.graphics.pdf.PdfRenderer and
 * compared with the source rendered the same way:
 * - on the marks (where the source is white) the export shows the mark colour: a highlighter is the
 *   page tinted with its colour (MULTIPLY over white), a pen its opaque colour;
 * - note cards show their fill colour, upright and un-mirrored (the text sits top-left);
 * - everywhere else the page is unchanged (vector) / has no new ink on paper that was blank (raster).
 * A mark that is shifted, mirrored, transposed or rotated fails both checks.
 */
@RunWith(AndroidJUnit4::class)
class AnnotatedPdfExportPositionTest {

    private val context = Fixtures.targetContext
    private val painter = AnnotationPainter()

    // ---- Tests -------------------------------------------------------------------------------------

    @Test
    fun vectorExport_marksLandWhereDrawn_onEveryPageType() {
        val dir = Fixtures.scratchDir("export-vector")
        val source = Fixtures.copy(Fixtures.LECTURE_NOTES, dir)
        val sourceBytes = source.readBytes()
        val out = File(dir, "exports/Biology Lecture Notes (annotated).pdf")

        val result = runBlocking { AnnotatedPdfExporter(context).export(source, ANNOTATIONS, out) }

        assertFalse("PdfBox handles the fixture: no raster fallback", result.usedRasterFallback)
        assertEquals(out, result.file)
        assertTrue(out.isFile)
        assertFalse("no partial file left", File(out.path + ".part").exists())
        assertTrue("the source is never modified", source.readBytes().contentEquals(sourceBytes))
        verifyExport(source, out, raster = false)
    }

    @Test
    fun vectorExport_addsNoteCommentsAtTheCardsTopRight() {
        val dir = Fixtures.scratchDir("export-comments")
        val source = Fixtures.copy(Fixtures.LECTURE_NOTES, dir)
        val out = File(dir, "annotated.pdf")
        runBlocking { AnnotatedPdfExporter(context).export(source, ANNOTATIONS, out) }

        PDDocument.load(out).use { doc ->
            assertEquals(12, doc.numberOfPages)
            for ((pageIndex, page) in ANNOTATIONS.pages) {
                val comments = doc.getPage(pageIndex).annotations.filterIsInstance<PDAnnotationText>()
                assertEquals("page ${pageIndex + 1}: one comment per note", page.notes.size, comments.size)
                for (note in page.notes) {
                    val comment = comments.single { it.annotationName == note.id }
                    assertEquals(note.text, comment.contents)
                    assertTrue(comment.isPrinted)
                    // Expected icon square (18 pt, 2 pt right of the card's top-right corner) mapped
                    // to unrotated user space with the table in ARCHITECTURE.md §9.
                    val card = painter.noteBounds(note)
                    val left = card.right + 2f
                    val top = card.top
                    val r = comment.rectangle
                    val (llx, lly) = when (pageIndex) {
                        Fixtures.PAGE_ROTATED_90 -> top to left // (llx + y, lly + x), crop = media = (0,0)
                        Fixtures.PAGE_CROPPED -> (40f + left) to (802f - top - 18f) // crop box (40,40)-(555,802)
                        else -> left to (pageHeight(pageIndex) - top - 18f) // (llx + x, ury - y)
                    }
                    val what = "page ${pageIndex + 1} comment rect $r"
                    assertEquals(what, llx, r.lowerLeftX, 0.5f)
                    assertEquals(what, lly, r.lowerLeftY, 0.5f)
                    assertEquals(what, 18f, r.width, 0.5f)
                    assertEquals(what, 18f, r.height, 0.5f)
                }
            }
            assertTrue(
                "unannotated pages get no comments",
                doc.getPage(1).annotations.none { it is PDAnnotationText },
            )
        }
    }

    @Test
    fun vectorExport_withoutNotes_leavesCardsAndCommentsOut() {
        val dir = Fixtures.scratchDir("export-no-notes")
        val source = Fixtures.copy(Fixtures.LECTURE_NOTES, dir)
        val out = File(dir, "annotated.pdf")
        runBlocking { AnnotatedPdfExporter(context).export(source, ANNOTATIONS, out, includeNotes = false) }

        PlatformPdf(out).use { pdf ->
            for ((pageIndex, page) in ANNOTATIONS.pages) {
                if (page.notes.isEmpty()) continue
                val pixels = pdf.render(pageIndex, SCALE)
                for (note in page.notes) {
                    val card = painter.noteBounds(note)
                    assertEquals("page ${pageIndex + 1}: no note card", 0, pixels.nonWhiteCount(card))
                }
            }
        }
        PDDocument.load(out).use { doc ->
            for (i in 0 until doc.numberOfPages) {
                assertTrue(doc.getPage(i).annotations.none { it is PDAnnotationText })
            }
        }
    }

    @Test
    fun rasterFallback_marksLandWhereDrawn_onEveryPageType() {
        val dir = Fixtures.scratchDir("export-raster")
        val source = Fixtures.copy(Fixtures.LECTURE_NOTES, dir)
        val out = File(dir, "raster.pdf")
        // The fallback AnnotatedPdfExporter uses when PdfBox cannot read a source; no fixture makes
        // PdfBox fail, so it is driven directly.
        runBlocking { RasterPdfWriter.write(source, ANNOTATIONS, out, includeNotes = true, tempDir = context.cacheDir) }
        assertTrue(out.isFile)
        verifyExport(source, out, raster = true)
    }

    @Test
    fun export_passwordProtectedSource_failsWithExportException() {
        val dir = Fixtures.scratchDir("export-password")
        val source = Fixtures.copy(Fixtures.PASSWORD, dir)
        val out = File(dir, "annotated.pdf")
        val e = runCatching { runBlocking { AnnotatedPdfExporter(context).export(source, ANNOTATIONS, out) } }.exceptionOrNull()
        assertTrue("expected ExportException, got $e", e is ExportException)
        assertTrue(e!!.message!!.contains("password", ignoreCase = true))
        assertFalse(out.exists())
    }

    // ---- Verification ------------------------------------------------------------------------------

    private fun verifyExport(source: File, out: File, raster: Boolean) {
        val failures = ArrayList<String>()
        PlatformPdf(source).use { src ->
            PlatformPdf(out).use { dst ->
                assertEquals("page count", src.pageCount, dst.pageCount)
                for (i in 0 until src.pageCount) {
                    assertEquals("page ${i + 1} size", src.pageSize(i), dst.pageSize(i))
                }
                for ((pageIndex, page) in ANNOTATIONS.pages.toSortedMap()) {
                    val before = src.render(pageIndex, SCALE)
                    val after = dst.render(pageIndex, SCALE)
                    for (stroke in page.strokes) checkStroke(pageIndex, stroke, before, after, raster, failures)
                    for (note in page.notes) checkNote(pageIndex, note, before, after, raster, failures)
                    checkElsewhere(pageIndex, page, before, after, raster, failures)
                }
                if (!raster) {
                    // Pages without annotations are left exactly as they were.
                    for (pageIndex in listOf(1, 11)) {
                        checkElsewhere(pageIndex, PageAnnotations.EMPTY, src.render(pageIndex, SCALE), dst.render(pageIndex, SCALE), false, failures)
                    }
                }
            }
        }
        failures.forEach { Log.e(TAG, it) }
        assertTrue("${failures.size} problems:\n" + failures.take(25).joinToString("\n"), failures.isEmpty())
    }

    /** Samples the stroke's centre line where the source paper is white: the export must show the ink colour. */
    private fun checkStroke(
        pageIndex: Int,
        stroke: Stroke,
        before: PagePixels,
        after: PagePixels,
        raster: Boolean,
        failures: MutableList<String>,
    ) {
        val expected = expectedOnWhite(stroke, raster)
        val tolerance = if (raster) RASTER_TOLERANCE else VECTOR_TOLERANCE
        val p = stroke.points
        var checked = 0
        for (k in 0 until SAMPLES) {
            val t = (k + 0.5f) / SAMPLES
            // Interpolate along the polyline (all fixture strokes are straight two-point segments).
            val x = p[0] + (p[p.size - 2] - p[0]) * t
            val y = p[1] + (p[p.size - 1] - p[1]) * t
            if (PagePixels.minChannel(before.at(x, y)) < WHITE) continue
            checked++
            val actual = after.at(x, y)
            if (PagePixels.channelDiff(actual, expected) > tolerance) {
                failures += "page ${pageIndex + 1} ${stroke.id}: at ($x, $y) expected ${PagePixels.hex(expected)} " +
                    "but export shows ${PagePixels.hex(actual)}"
            }
        }
        if (checked < SAMPLES * 3 / 4) {
            failures += "page ${pageIndex + 1} ${stroke.id}: fixture assumption broken, only $checked samples on white paper"
        }
    }

    /** The card's padding shows its fill colour and its text sits top-left (upright, not mirrored). */
    private fun checkNote(
        pageIndex: Int,
        note: Note,
        before: PagePixels,
        after: PagePixels,
        raster: Boolean,
        failures: MutableList<String>,
    ) {
        val card = painter.noteBounds(note)
        val what = "page ${pageIndex + 1} note ${note.id} card $card"
        if (before.nonWhiteCount(card) != 0) failures += "$what: fixture assumption broken, source not blank there"
        val tolerance = if (raster) RASTER_TOLERANCE else VECTOR_TOLERANCE
        val inset = 3f
        val corners = listOf(
            card.left + inset to card.top + inset,
            card.right - inset to card.top + inset,
            card.left + inset to card.bottom - inset,
            card.right - inset to card.bottom - inset,
            card.centerX() + 6f to card.centerY(),
        )
        for ((x, y) in corners) {
            val actual = after.at(x, y)
            if (PagePixels.channelDiff(actual, note.color) > tolerance) {
                failures += "$what: at ($x, $y) expected fill ${PagePixels.hex(note.color)} but export shows ${PagePixels.hex(actual)}"
            }
        }
        // "M" is the only glyph: top-left of the card. Mirrored/flipped placements move it.
        val topLeft = RectF(card.left + 5f, card.top + 5f, card.left + 16f, card.top + 17f)
        val topRight = RectF(card.right - 16f, card.top + 5f, card.right - 5f, card.top + 17f)
        val bottomLeft = RectF(card.left + 5f, card.bottom - 17f, card.left + 16f, card.bottom - 5f)
        val glyph = after.darkCount(topLeft, GLYPH_LUMINANCE)
        if (glyph < 4) failures += "$what: note text not at the top-left ($glyph dark px)"
        for ((name, r) in listOf("top-right" to topRight, "bottom-left" to bottomLeft)) {
            val n = after.darkCount(r, GLYPH_LUMINANCE)
            if (n > 0) failures += "$what: $n dark px at the $name: card mirrored or flipped"
        }
    }

    /**
     * Away from every mark the export must look like the source: identical (vector), or at least no
     * darker or more colourful than the source nearby (raster: JPEG pages).
     */
    private fun checkElsewhere(
        pageIndex: Int,
        page: PageAnnotations,
        before: PagePixels,
        after: PagePixels,
        raster: Boolean,
        failures: MutableList<String>,
    ) {
        val excluded = ArrayList<RectF>()
        for (note in page.notes) {
            val card = painter.noteBounds(note)
            excluded += RectF(card.left - 3f, card.top - 3f, card.right + 3f, card.bottom + 4f)
            // The comment icon (not drawn by PdfRenderer without annotation flags, but allowed).
            excluded += RectF(card.right, card.top - 3f, card.right + 24f, card.top + 22f)
        }
        var problems = 0
        var samples = 0
        var y = 1f
        while (y < before.heightPt) {
            var x = 1f
            while (x < before.widthPt) {
                if (!nearMark(x, y, page.strokes) && excluded.none { it.contains(x, y) }) {
                    samples++
                    val s = before.at(x, y)
                    val a = after.at(x, y)
                    val bad = if (raster) {
                        // The page is a JPEG of a 2x render, shown here at 3x: glyph edges blur by a
                        // pixel or two. Compare with the darkest / most colourful source pixel nearby,
                        // so only new ink on paper that was blank around the sample counts.
                        PagePixels.luminance(a) < before.minLuminanceAround(x, y, RASTER_BLUR_PT) - RASTER_TOLERANCE ||
                            PagePixels.chroma(a) > before.maxChromaAround(x, y, RASTER_BLUR_PT) + RASTER_TOLERANCE
                    } else {
                        PagePixels.channelDiff(s, a) > VECTOR_TOLERANCE
                    }
                    if (bad) {
                        problems++
                        if (problems <= 5) {
                            failures += "page ${pageIndex + 1}: changed away from the marks at ($x, $y): " +
                                "source ${PagePixels.hex(s)}, export ${PagePixels.hex(a)}"
                        }
                    }
                }
                x += GRID_STEP
            }
            y += GRID_STEP
        }
        if (problems > 5) failures += "page ${pageIndex + 1}: … $problems of $samples samples changed away from the marks"
    }

    private fun nearMark(x: Float, y: Float, strokes: List<Stroke>): Boolean = strokes.any { s ->
        val p = s.points
        val reach = s.width / 2f + 2.5f
        var near = false
        for (i in 0 until s.pointCount - 1) {
            if (distanceToSegment(x, y, p[2 * i], p[2 * i + 1], p[2 * i + 2], p[2 * i + 3]) <= reach) near = true
        }
        near
    }

    private fun distanceToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /**
     * Colour of the stroke's centre over white paper. Pen: its colour. Highlighter in the raster path:
     * its colour (MULTIPLY with white). Highlighter in the vector path: /BM /Multiply at 55 % opacity
     * with the compensated colour (ARCHITECTURE.md §9) gives 0.45 + 0.55 * c' = max(c, 0.45) per
     * channel on white.
     */
    private fun expectedOnWhite(stroke: Stroke, raster: Boolean): Int {
        val c = stroke.color
        if (stroke.tool == InkTool.PEN || raster) return c or OPAQUE
        val floor = (255 * (1f - HighlightColor.ALPHA)).roundToInt()
        return Color.rgb(max(Color.red(c), floor), max(Color.green(c), floor), max(Color.blue(c), floor))
    }

    private fun pageHeight(pageIndex: Int): Float = when (pageIndex) {
        Fixtures.PAGE_ROTATED_90 -> 595f
        Fixtures.PAGE_CROPPED -> 762f
        Fixtures.PAGE_LANDSCAPE -> 612f
        else -> 842f
    }

    private companion object {
        const val TAG = "ExportPositionTest"
        const val OPAQUE = 0xFF000000.toInt()

        /** Render scale for verification, px per point. */
        const val SCALE = 3f

        const val SAMPLES = 12
        const val GRID_STEP = 3f

        /** A source pixel this light (every channel) counts as blank paper. */
        const val WHITE = 245

        /** Raster pages are JPEG re-sampled: glyph edges may spread this far (page points). */
        const val RASTER_BLUR_PT = 2f
        const val VECTOR_TOLERANCE = 12
        const val RASTER_TOLERANCE = 30

        /** Note text (#1F1F24) vs. the yellow card fill (luminance ~238). */
        const val GLYPH_LUMINANCE = 180

        private val YELLOW = Palette.HIGHLIGHTER_COLORS[0] // 0xFFFFE94D: blue channel below 0.45 -> clamped
        private val GREEN = Palette.HIGHLIGHTER_COLORS[1]
        private val PINK = Palette.HIGHLIGHTER_COLORS[3]
        private val INK_BLACK = Palette.PEN_COLORS[0]
        private val INK_BLUE = Palette.PEN_COLORS[1]
        private val INK_RED = Palette.PEN_COLORS[2]
        private val INK_GREEN = Palette.PEN_COLORS[3]

        private fun hl(id: String, color: Int, vararg pts: Float) = Stroke(id, InkTool.HIGHLIGHTER, color, 14f, pts)
        private fun pen(id: String, color: Int, vararg pts: Float) = Stroke(id, InkTool.PEN, color, 3f, pts)
        private fun note(id: String, x: Float, y: Float, color: Int) = Note(id, x, y, "M\n \n ", color, 1_700_000_000_000L, 1_700_000_000_000L)

        /**
         * Page-space marks on blank paper (see the fixture layout in PdfDocumentHandleDeviceTest):
         * between the title and the first paragraph, in the side margins, and in the gaps between
         * paragraphs. Each is placed so that a rotated / mirrored / un-cropped copy would land elsewhere.
         */
        val ANNOTATIONS = DocumentAnnotations(
            pages = mapOf(
                // Page 1: plain A4 (595 x 842).
                Fixtures.PAGE_PLAIN to PageAnnotations(
                    strokes = listOf(
                        hl("p1-highlight", YELLOW, 380f, 93f, 530f, 93f),
                        pen("p1-pen", INK_RED, 567f, 150f, 567f, 400f),
                    ),
                    notes = listOf(note("p1-note", 440f, 730f, Palette.NOTE_COLORS[0])),
                ),
                // Page 5: /Rotate 90, displayed 842 x 595 (unrotated (u, v) shows at (842 - v, u)).
                Fixtures.PAGE_ROTATED_90 to PageAnnotations(
                    strokes = listOf(
                        hl("p5-highlight", GREEN, 300f, 22f, 700f, 22f),
                        pen("p5-pen", INK_BLUE, 235f, 100f, 235f, 350f),
                    ),
                    notes = listOf(note("p5-note", 605f, 400f, Palette.NOTE_COLORS[1])),
                ),
                // Page 6: crop box (40, 40)-(555, 802), displayed 515 x 762.
                Fixtures.PAGE_CROPPED to PageAnnotations(
                    strokes = listOf(
                        hl("p6-highlight", PINK, 100f, 170f, 400f, 170f),
                        pen("p6-pen", INK_BLACK, 60f, 290f, 450f, 318f),
                    ),
                    notes = listOf(note("p6-note", 300f, 690f, Palette.NOTE_COLORS[3])),
                ),
                // Page 7: Letter landscape (792 x 612).
                Fixtures.PAGE_LANDSCAPE to PageAnnotations(
                    strokes = listOf(
                        hl("p7-highlight", YELLOW, 150f, 210f, 650f, 210f),
                        pen("p7-pen", INK_GREEN, 100f, 330f, 700f, 362f),
                    ),
                    notes = listOf(note("p7-note", 600f, 60f, Palette.NOTE_COLORS[2])),
                ),
            ),
        )
    }
}
