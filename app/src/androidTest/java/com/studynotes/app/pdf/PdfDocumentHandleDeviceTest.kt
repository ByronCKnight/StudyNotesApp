package com.studynotes.app.pdf

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRendererPreV
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.SizeF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studynotes.app.testutil.Fixtures
import com.studynotes.app.testutil.PagePixels
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.roundToInt

/**
 * [PdfDocumentHandle] against the real platform renderer on the test fixtures: page geometry in page
 * space (crop box and /Rotate applied), rendering, text selection (MODERN backend only) and failures.
 *
 * Fixture layout (tools/make_fixtures.py, PyMuPDF coordinates = top-left origin, unrotated):
 * every text page has a 20 pt Helvetica title with its baseline at (56, 72), 11 pt paragraphs in
 * boxes from x = 56 to width - 56 starting at y = 110 every 132 pt, and nothing in the side margins.
 */
@RunWith(AndroidJUnit4::class)
class PdfDocumentHandleDeviceTest {

    private fun open(name: String, sizeCacheDir: File? = null): PdfDocumentHandle =
        runBlocking { PdfDocumentHandle.open(Fixtures.copy(name), sizeCacheDir) }

    private inline fun <T> withHandle(name: String, block: (PdfDocumentHandle) -> T): T {
        val handle = open(name)
        try {
            return block(handle)
        } finally {
            handle.close()
        }
    }

    // ---- Geometry ----------------------------------------------------------------------------------

    @Test
    fun lectureNotes_pageCountAndPageSpaceSizes() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        assertEquals(12, h.pageCount)
        assertEquals(12, h.pageSizes.size)
        for (i in 0 until 12) {
            val expected = when (i) {
                Fixtures.PAGE_ROTATED_90 -> SizeF(842f, 595f) // A4 portrait with /Rotate 90: displayed landscape
                Fixtures.PAGE_CROPPED -> SizeF(515f, 762f) // crop box (40,40)-(555,802)
                Fixtures.PAGE_LANDSCAPE -> SizeF(792f, 612f) // Letter landscape
                else -> SizeF(595f, 842f)
            }
            assertEquals("page ${i + 1} size", expected, h.pageSizes[i])
        }
    }

    @Test
    fun otherFixtures_pageCountsAndSizes() {
        withHandle(Fixtures.ANNOTATED_ELSEWHERE) { h ->
            assertEquals(1, h.pageCount)
            assertEquals(SizeF(595f, 842f), h.pageSizes[0])
        }
        withHandle(Fixtures.SCANNED) { h ->
            assertEquals(1, h.pageCount)
            assertEquals(SizeF(595f, 842f), h.pageSizes[0])
        }
        withHandle(Fixtures.TEXTBOOK) { h ->
            assertEquals(300, h.pageCount)
            assertTrue(h.pageSizes.all { it == SizeF(595f, 842f) })
        }
    }

    @Test
    fun pageSizeCache_isWrittenAndReused() {
        val cacheDir = Fixtures.scratchDir("pagesizes")
        val first = open(Fixtures.LECTURE_NOTES, cacheDir)
        val sizes = try { first.pageSizes } finally { first.close() }
        assertTrue("a size cache file is written", cacheDir.listFiles().orEmpty().isNotEmpty())
        val second = open(Fixtures.LECTURE_NOTES, cacheDir)
        try {
            assertEquals(sizes, second.pageSizes)
        } finally {
            second.close()
        }
    }

    // ---- Rendering ---------------------------------------------------------------------------------

    @Test
    fun renderPage_plainPage_hasTitleInkAndWhiteMargins() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        val page = render(h, Fixtures.PAGE_PLAIN)
        assertEquals("opaque white background", Color.WHITE, page.at(5f, 5f))
        assertTrue("title ink", page.darkCount(RectF(56f, 56f, 200f, 76f)) > 150)
        assertTrue("paragraph ink", page.darkCount(RectF(56f, 110f, 539f, 160f)) > 500)
        assertEquals("right margin is blank", 0, page.nonWhiteCount(RectF(548f, 0f, 595f, 842f)))
        assertEquals("left margin is blank", 0, page.nonWhiteCount(RectF(0f, 0f, 50f, 842f)))
    }

    @Test
    fun renderPage_rotatedPage_titleRunsDownTheRightEdge() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        val page = render(h, Fixtures.PAGE_ROTATED_90)
        assertEquals(842f, page.widthPt)
        assertEquals(595f, page.heightPt)
        // /Rotate 90 turns the page clockwise: unrotated (u, v) is displayed at (842 - v, u). The title
        // (u 56..200, v 57..76) becomes a vertical band near the right edge.
        assertTrue("title ink, rotated", page.darkCount(RectF(764f, 56f, 787f, 200f)) > 150)
        // Where the title would be without rotation is a blank margin of the rotated page.
        assertEquals("unrotated title spot is blank", 0, page.nonWhiteCount(RectF(56f, 0f, 300f, 48f)))
        // The unrotated top margin (v < 50) is the displayed right strip.
        assertEquals(0, page.nonWhiteCount(RectF(795f, 0f, 842f, 595f)))
    }

    @Test
    fun renderPage_croppedPage_contentShiftedByCropOrigin() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        val page = render(h, Fixtures.PAGE_CROPPED)
        assertEquals(515f, page.widthPt)
        assertEquals(762f, page.heightPt)
        // Crop box origin (40, 40): the title moves from (56..200, 57..76) to (16..160, 17..36).
        assertTrue("title ink, shifted by the crop origin", page.darkCount(RectF(16f, 16f, 160f, 36f)) > 150)
        assertEquals("nothing left of the shifted text", 0, page.nonWhiteCount(RectF(0f, 0f, 10f, 762f)))
    }

    @Test
    fun renderPage_landscapePage() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        val page = render(h, Fixtures.PAGE_LANDSCAPE)
        assertEquals(792f, page.widthPt)
        assertEquals(612f, page.heightPt)
        assertTrue("title ink", page.darkCount(RectF(56f, 56f, 200f, 76f)) > 150)
        assertTrue("paragraph ink spans the wide page", page.darkCount(RectF(600f, 110f, 736f, 160f)) > 100)
        assertEquals(0, page.nonWhiteCount(RectF(745f, 0f, 792f, 612f)))
    }

    @Test
    fun renderPage_scannedImagePage_isNotBlank() = withHandle(Fixtures.SCANNED) { h ->
        val page = render(h, 0)
        // Dark stripes every 18 image rows: plenty of dark pixels across the whole page.
        assertTrue(page.darkCount(RectF(0f, 0f, 595f, 842f)) > 10_000)
    }

    @Test
    fun renderRegion_rendersRequestedAreaAndReusesBitmap() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        val region = RectF(50f, 50f, 250f, 90f)
        val scale = 3f
        val bitmap = runBlocking { h.renderRegion(0, region, scale) }
        try {
            assertEquals(600, bitmap.width)
            assertEquals(120, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            val pixels = PagePixels(bitmap, scale, region.width(), region.height())
            // Title glyphs at page (56..200, 57..76) = region-local (6..150, 7..26).
            assertTrue(pixels.darkCount(RectF(6f, 7f, 150f, 26f)) > 300)
            assertEquals(0, pixels.nonWhiteCount(RectF(0f, 0f, 200f, 4f)))

            val again = runBlocking { h.renderRegion(0, region, scale, reuse = bitmap) }
            assertSame("a matching bitmap is reused", bitmap, again)
        } finally {
            bitmap.recycle()
        }
        val outside = runBlocking { h.renderRegion(0, RectF(700f, 900f, 800f, 1000f), 2f) }
        assertEquals("a region off the page is a 1x1 white bitmap", 1, outside.width * outside.height)
        assertEquals(Color.WHITE, outside.getPixel(0, 0))
    }

    @Test
    fun renderFirstPage_eachPdfFixture() {
        for (name in listOf(Fixtures.LECTURE_NOTES, Fixtures.ANNOTATED_ELSEWHERE, Fixtures.SCANNED, Fixtures.TEXTBOOK)) {
            val (bitmap, size) = runBlocking { PdfDocumentHandle.renderFirstPage(Fixtures.copy(name), 360, 720) }
            try {
                assertEquals(name, SizeF(595f, 842f), size)
                assertEquals(360, bitmap.width)
                assertEquals("$name: height follows the aspect ratio", (360 * 842f / 595f).roundToInt(), bitmap.height)
                val pixels = PagePixels(bitmap, 360f / 595f, 595f, 842f)
                assertTrue("$name thumbnail is not blank", pixels.darkCount(RectF(0f, 0f, 595f, 842f)) > 100)
            } finally {
                bitmap.recycle()
            }
        }
        val (cut, _) = runBlocking { PdfDocumentHandle.renderFirstPage(Fixtures.copy(Fixtures.LECTURE_NOTES), 360, 200) }
        assertEquals("cut off at maxHeightPx", 200, cut.height)
        cut.recycle()
    }

    // ---- Text selection ----------------------------------------------------------------------------

    @Test
    fun textLayout_modern_linesWordsAndSelectionInPageSpace() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        assumeTrue("text selection needs the MODERN backend (API 35+ or S extension 13)", h.supportsTextSelection)
        val layout = runBlocking { h.textLayout(0) }
        assertNotNull("page text", layout)
        assertFalse(layout!!.isVertical)
        Log.i(TAG, "page 1: ${layout.lineCount} lines, first ${layout.lines.first().words.map { it.box }}")
        // Reading order starts with the title "Chapter 1: Photosynthesis": three words on one line.
        val title = layout.lines.first()
        assertEquals("title words: ${title.words.map { it.box }}", 3, title.words.size)
        assertTrue("left edge near the title start: ${title.box}", title.box.left in 50f..70f)
        assertTrue("top above the cap height: ${title.box}", title.box.top in 50f..66f)
        assertTrue("bottom below the baseline: ${title.box}", title.box.bottom in 70f..82f)
        assertTrue("a 20 pt line: ${title.box}", title.box.height in 12f..30f)

        // Across the title, through the middle of its x-height, ending mid-word: whole words.
        val line = layout.select(60f, 70f, 250f, 70f)!!.boxes.single()
        Log.i(TAG, "title selection: $line")
        assertEquals("from the first word: $line", title.box.left, line.left, 0.01f)
        assertTrue("to the end of the last word touched: $line", line.right in 282f..298f)

        // Two lines of the first paragraph: one box per line, top to bottom, inside the text box.
        val para = layout.select(60f, 120f, 500f, 134f)!!.boxes
        Log.i(TAG, "paragraph selection: $para")
        assertEquals("one box per line: $para", 2, para.size)
        assertTrue(para[0].top < para[1].top)
        for (r in para) {
            assertTrue("inside the text box: $r", r.left >= 54f && r.right <= 541f)
            assertTrue("11 pt line height: $r", r.height in 6f..20f)
            assertTrue("in the first paragraph: $r", r.top >= 105f && r.bottom <= 145f)
        }

        // Word at point: identical start and end inside a word select that word.
        val word = layout.select(80f, 70f, 80f, 70f)!!.boxes.single()
        Log.i(TAG, "word selection: $word")
        assertTrue("the word under the point: $word", word.left <= 80f && word.right >= 80f && word.width < 120f)

        assertNull("out-of-range page", runBlocking { h.textLayout(99) })
        assertSame("cached per page", layout, runBlocking { h.textLayout(0) })
    }

    /** Bounds of every occurrence of [word] on [page], straight from the platform's text search. */
    @SuppressLint("NewApi") // Callers check the MODERN backend first.
    private fun wordBounds(name: String, page: Int, word: String): List<RectF> {
        val file = Fixtures.copy(name)
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRendererPreV(fd).use { renderer ->
                renderer.openPage(page).use { p -> return p.searchText(word).flatMap { it.bounds } }
            }
        }
    }

    @Test
    fun textLayout_modern_selectsWholeWords() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        assumeTrue("text selection needs the MODERN backend", h.supportsTextSelection)
        val layout = runBlocking { h.textLayout(0) }!!
        // First paragraph: "... light-dependent reactions, water is split and oxygen is released ..."
        val reactions = wordBounds(Fixtures.LECTURE_NOTES, 0, "reactions").minBy { it.top }
        val oxygen = wordBounds(Fixtures.LECTURE_NOTES, 0, "oxygen")
            .filter { kotlin.math.abs(it.centerY() - reactions.centerY()) < 3f }
            .minBy { it.left }
        Log.i(TAG, "reactions $reactions, oxygen $oxygen")
        // From inside the "e" of "reactions" to inside the "n" of "oxygen": the highlight a slightly
        // short stroke asks for. It must still cover both words entirely.
        val line = layout.select(
            reactions.left + reactions.width() * 0.12f, reactions.centerY(),
            oxygen.right - oxygen.width() * 0.08f, oxygen.centerY(),
        )!!.boxes.single()
        Log.i(TAG, "inside-word selection: $line")
        assertEquals("starts at the start of \"reactions\": $line", reactions.left, line.left, 1.5f)
        assertEquals("ends at the end of \"oxygen\": $line", oxygen.right, line.right, 1.5f)

        // Ending just past a word, in the gap after it: the whole word and nothing more.
        val gap = layout.select(oxygen.left + oxygen.width() * 0.3f, oxygen.centerY(), oxygen.right + 1f, oxygen.centerY())!!.boxes.single()
        Log.i(TAG, "into-the-gap selection: $gap")
        assertEquals("starts at the start of \"oxygen\": $gap", oxygen.left, gap.left, 1.5f)
        assertEquals("stops at the end of \"oxygen\": $gap", oxygen.right, gap.right, 1.5f)

        // A drag inside a three-letter word ("the", which the page is full of) is that whole word.
        val the = wordBounds(Fixtures.LECTURE_NOTES, 0, "the ").minWith(compareBy({ it.top }, { it.left }))
        for ((from, to) in listOf(3.5f to 12f, 5.8f to 11f, 7.3f to 12.5f)) {
            val inside = layout.select(the.left + from, the.centerY(), the.left + to, the.centerY())!!.boxes
            Log.i(TAG, "inside \"the\" ${the.left + from}..${the.left + to}: $inside")
            val r = inside.single()
            assertEquals("whole \"the\" from ${the.left + from}: $r", the.left, r.left, 1.5f)
            assertTrue("whole \"the\" (without the space) from ${the.left + from}: $r", r.right in (the.right - 6f)..(the.right + 0.5f))
        }
    }

    @Test
    fun textLayout_modern_rotatedPage_isVerticalInDisplayedPageSpace() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        assumeTrue("text selection needs the MODERN backend", h.supportsTextSelection)
        val layout = runBlocking { h.textLayout(Fixtures.PAGE_ROTATED_90) }!!
        assertTrue("lines read down the screen", layout.isVertical)
        // The rotated page's title is a vertical line near x = 766..785 (displayed), y = 56..330.
        val boxes = layout.select(776f, 60f, 776f, 250f)!!.boxes
        Log.i(TAG, "rotated title selection: $boxes")
        assertTrue("selection on the rotated page", boxes.isNotEmpty())
        for (r in boxes) {
            assertTrue("box in displayed page space (right edge, vertical): $r", r.left >= 740f && r.right <= 800f)
            assertTrue("box along the title: $r", r.top >= 40f && r.bottom <= 345f)
        }
    }

    @Test
    fun textLayout_modern_croppedPage_isRelativeToCropBox() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        assumeTrue("text selection needs the MODERN backend", h.supportsTextSelection)
        val layout = runBlocking { h.textLayout(Fixtures.PAGE_CROPPED) }!!
        // Title shifted by the crop origin (40, 40): baseline at y = 32, starting at x = 16.
        val line = layout.select(20f, 30f, 200f, 30f)!!.boxes.single()
        Log.i(TAG, "cropped title selection: $line")
        assertTrue("left edge shifted by the crop box: $line", line.left in 10f..30f)
        assertTrue("top shifted by the crop box: $line", line.top in 10f..26f)
        assertTrue("bottom shifted by the crop box: $line", line.bottom in 30f..42f)
    }

    @Test
    fun textLayout_legacy_isUnsupported() = withHandle(Fixtures.LECTURE_NOTES) { h ->
        assumeFalse("only meaningful on the LEGACY backend", h.supportsTextSelection)
        assertNull(runBlocking { h.textLayout(0) })
    }

    @Test
    fun foreignHighlightAnnotation_isRenderedOnModern() = withHandle(Fixtures.ANNOTATED_ELSEWHERE) { h ->
        assumeTrue("annotation rendering flags need the MODERN backend", PdfBackend.isModernAvailable)
        val page = render(h, 0)
        // PyMuPDF's default highlight is yellow: count clearly yellow pixels anywhere on the page.
        var yellow = 0
        for (y in 0 until 842 step 1) for (x in 0 until 595 step 1) {
            val c = page.at(x + 0.25f, y + 0.25f)
            if (Color.red(c) > 200 && Color.green(c) > 200 && Color.blue(c) < 120) yellow++
        }
        assertTrue("the other app's highlight is visible ($yellow yellow samples)", yellow > 100)
    }

    // ---- Failures ----------------------------------------------------------------------------------

    @Test
    fun passwordProtectedPdf_isReportedAsPasswordProtected() {
        val e = expectOpenFailure(Fixtures.copy(Fixtures.PASSWORD))
        assertEquals(PdfOpenException.Reason.PASSWORD_PROTECTED, e.reason)
        assertTrue(e.message!!.isNotBlank())
        val e2 = runCatching { runBlocking { PdfDocumentHandle.renderFirstPage(Fixtures.copy(Fixtures.PASSWORD), 100, 100) } }
            .exceptionOrNull()
        assertTrue("renderFirstPage: $e2", e2 is PdfOpenException && e2.reason == PdfOpenException.Reason.PASSWORD_PROTECTED)
    }

    @Test
    fun corruptInputs_failCleanlyAsCorrupt() {
        val dir = Fixtures.scratchDir("corrupt-pdf")
        val cases = mapOf(
            "text.pdf" to "This is not a PDF at all.\n".toByteArray(),
            "empty.pdf" to ByteArray(0),
            "zip.pdf" to Fixtures.bytes(Fixtures.CORRUPT_DOCX),
            "header-only.pdf" to "%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1),
        )
        for ((name, bytes) in cases) {
            val file = File(dir, name).apply { writeBytes(bytes) }
            val e = expectOpenFailure(file)
            assertEquals("$name: ${e.message}", PdfOpenException.Reason.CORRUPT, e.reason)
        }
    }

    @Test
    fun truncatedPdf_opensRepairedOrFailsAsCorrupt() {
        val bytes = Fixtures.bytes(Fixtures.LECTURE_NOTES)
        val file = File(Fixtures.scratchDir("truncated-pdf"), "truncated.pdf").apply { writeBytes(bytes.copyOf(bytes.size / 2)) }
        val result = runCatching { runBlocking { PdfDocumentHandle.open(file) } }
        val handle = result.getOrNull()
        if (handle != null) {
            try {
                assertTrue(handle.pageCount in 1..12)
                // Whatever PDFium recovered must render without throwing.
                runBlocking { handle.renderPage(0, 100) }.recycle()
            } finally {
                handle.close()
            }
        } else {
            val e = result.exceptionOrNull()
            assertTrue("only PdfOpenException(CORRUPT) is acceptable, got $e", e is PdfOpenException && e.reason == PdfOpenException.Reason.CORRUPT)
        }
    }

    @Test
    fun missingFile_isNotFound() {
        val e = expectOpenFailure(File(Fixtures.scratchDir("missing"), "nope.pdf"))
        assertEquals(PdfOpenException.Reason.NOT_FOUND, e.reason)
    }

    @Test
    fun closedHandle_rejectsRenderingAndSelection() {
        val h = open(Fixtures.LECTURE_NOTES)
        h.close()
        h.close() // idempotent
        assertTrue(h.isClosed)
        val e = runCatching { runBlocking { h.renderPage(0, 100) } }.exceptionOrNull()
        assertTrue("renderPage after close: $e", e is IllegalStateException)
        assertNull(runBlocking { h.textLayout(0) })
    }

    // ---- Helpers -----------------------------------------------------------------------------------

    private fun render(h: PdfDocumentHandle, index: Int, scale: Float = 2f): PagePixels {
        val size = h.pageSizes[index]
        val bitmap = runBlocking { h.renderPage(index, (size.width * scale).roundToInt()) }
        try {
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            return PagePixels(bitmap, bitmap.width / size.width, size.width, size.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun expectOpenFailure(file: File): PdfOpenException {
        try {
            runBlocking { PdfDocumentHandle.open(file) }.close()
        } catch (e: PdfOpenException) {
            return e
        } catch (t: Throwable) {
            fail("${file.name}: expected PdfOpenException, got $t")
        }
        fail("${file.name}: expected PdfOpenException, but it opened")
        throw AssertionError()
    }

    private companion object {
        const val TAG = "PdfHandleDeviceTest"
    }
}
