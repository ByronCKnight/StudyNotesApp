package com.studynotes.app.docx

import android.graphics.RectF
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.testutil.Fixtures
import com.studynotes.app.testutil.PlatformPdf
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * DOCX → PDF on the device: [DocxConverter] (real XmlPullParser, StaticLayout and
 * android.graphics.pdf.PdfDocument) and [DocxRenditionManager] bookkeeping. The output is checked
 * with the platform PdfRenderer (what the editor opens) and PdfBox text extraction (the text must stay
 * real, selectable text).
 *
 * Content of the fixtures: tools/make_fixtures.py. "Cell Biology Study Guide.docx" is A4 with 1-inch
 * margins: a first part (title, lists, table, image + "Figure 1" caption), an explicit page break,
 * then "2. Cell division" with 14 long paragraphs, a w:del ("GONE", must vanish), a w:ins (must
 * appear), and one footnote that is appended under a "Notes" heading at the end.
 */
@RunWith(AndroidJUnit4::class)
class DocxConversionDeviceTest {

    @Test
    fun studyGuide_convertsToA4PdfWithExpectedPagesAndRealText() {
        val dir = Fixtures.scratchDir("docx-guide")
        val docx = Fixtures.copy(Fixtures.STUDY_GUIDE_DOCX, dir)
        val out = File(dir, "guide.pdf")
        DocxConverter.convert(docx, out)
        assertTrue(out.isFile && out.length() > 0)
        assertFalse("no temp file left behind", File(dir, "guide.pdf.tmp").exists())

        val pageCount = PlatformPdf(out).use { pdf ->
            for (i in 0 until pdf.pageCount) {
                assertEquals("page ${i + 1} is A4 (w:pgSz 11906 x 16838 twips)", 595 to 842, pdf.pageSize(i))
                val page = pdf.render(i, 1.5f)
                val ink = page.darkCount(RectF(0f, 0f, 595f, 842f))
                Log.i(TAG, "study guide page ${i + 1}: $ink dark px")
                assertTrue("page ${i + 1} has text", ink > 1_000)
                // 1-inch margins: nothing (but maybe a hanging bullet) in the outer 0.5 inch.
                assertEquals("page ${i + 1} left edge blank", 0, page.nonWhiteCount(RectF(0f, 0f, 36f, 842f)))
                assertEquals("page ${i + 1} right edge blank", 0, page.nonWhiteCount(RectF(559f, 0f, 595f, 842f)))
                assertEquals("page ${i + 1} top edge blank", 0, page.nonWhiteCount(RectF(0f, 0f, 595f, 36f)))
            }
            pdf.pageCount
        }

        val texts = pageTexts(out)
        texts.forEachIndexed { i, t -> Log.i(TAG, "study guide page ${i + 1} text: ${t.take(160).replace('\n', '|')} …") }
        assertEquals(pageCount, texts.size)
        assertEquals("page count", STUDY_GUIDE_PAGES, pageCount)

        val all = texts.joinToString("\n")
        assertTrue("title", all.contains("Study Guide"))
        assertTrue("table", all.contains("Mitochondrion"))
        assertTrue("w:ins content appears", all.contains("inserted text should appear"))
        assertFalse("w:del content is dropped", all.contains("GONE"))
        assertTrue("footnote text appended", all.contains("Singer"))

        // The explicit page break: "2. Cell division" starts a new page, after the figure caption.
        val captionPage = texts.indexOfFirst { it.contains("Figure 1") }
        val divisionPage = texts.indexOfFirst { it.contains("Cell division") }
        assertTrue("caption found", captionPage >= 0)
        assertTrue("section 2 is on a later page than the caption", divisionPage > captionPage)
        assertTrue(
            "section 2 heading is the first text on its page",
            texts[divisionPage].trimStart().startsWith("2. Cell division"),
        )
        // The footnote is under a "Notes" heading on the last page.
        assertTrue(texts.last().contains("Notes") && texts.last().contains("Singer"))

        // The inline picture (gradient rgb(40..199, 90..169, 200), extent 3048000 x 1524000 EMU =
        // 240 x 120 pt) is drawn on the caption's page, centred above the caption.
        PlatformPdf(out).use { pdf ->
            val page = pdf.render(captionPage, 1f)
            var blue = 0
            var minX = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            for (y in 0 until 842) {
                // Only rows the picture fills: blue text (the hyperlink) on the same page has a
                // similar colour but never 100 such pixels in one row.
                val hits = (0 until 595).filter { x -> isPictureColour(page.at(x + 0.5f, y + 0.5f)) }
                if (hits.size < 100) continue
                blue += hits.size
                minX = minOf(minX, hits.first().toFloat())
                maxX = maxOf(maxX, hits.last().toFloat())
            }
            Log.i(TAG, "picture on page ${captionPage + 1}: $blue px, x $minX..$maxX")
            assertTrue("picture drawn on the caption page ($blue px)", blue > 10_000)
            assertEquals("picture width from its extent", 240f, maxX - minX + 1f, 3f)
            assertEquals("picture centred (jc=center)", 595f / 2f, (minX + maxX + 1f) / 2f, 3f)
        }
    }

    @Test
    fun minimalDocx_withoutStylesPart_convertsToOneDefaultPage() {
        val dir = Fixtures.scratchDir("docx-minimal")
        val docx = Fixtures.copy(Fixtures.MINIMAL_DOCX, dir)
        val out = File(dir, "minimal.pdf")
        DocxConverter.convert(docx, out)

        PlatformPdf(out).use { pdf ->
            assertEquals(1, pdf.pageCount)
            val default = DocxPageSetup.DEFAULT
            assertEquals("default page size", default.widthPt.toInt() to default.heightPt.toInt(), pdf.pageSize(0))
            val page = pdf.render(0, 2f)
            // Two short paragraphs at the top-left margin, the rest of the page blank.
            assertTrue(page.darkCount(RectF(72f, 72f, 400f, 120f)) > 200)
            assertEquals(0, page.nonWhiteCount(RectF(0f, 200f, page.widthPt, page.heightPt)))
        }
        val text = pageTexts(out).single()
        assertTrue(text.contains("Minimal document with no styles part."))
        assertTrue(text.contains("Second paragraph."))
    }

    @Test
    fun corruptDocx_raisesDocxConversionException_andLeavesNoOutput() {
        val dir = Fixtures.scratchDir("docx-corrupt")
        val docx = Fixtures.copy(Fixtures.CORRUPT_DOCX, dir)
        val out = File(dir, "corrupt.pdf")
        try {
            DocxConverter.convert(docx, out)
            fail("Corrupt.docx must not convert")
        } catch (e: DocxConversionException) {
            assertTrue("user-presentable message", !e.message.isNullOrBlank())
            Log.i(TAG, "Corrupt.docx: ${e.message}")
        }
        assertFalse(out.exists())
        assertFalse(File(dir, "corrupt.pdf.tmp").exists())
    }

    @Test
    fun renditionManager_corruptDocx_raisesDocxConversionException() {
        val paths = libraryPaths("lib-corrupt")
        val docx = Fixtures.copy(Fixtures.CORRUPT_DOCX, paths.root)
        val manager = DocxRenditionManager(Fixtures.targetContext)
        val e = runCatching { runBlocking { manager.ensureRendition(paths, docx) } }.exceptionOrNull()
        assertTrue("expected DocxConversionException, got $e", e is DocxConversionException)
        assertNull(manager.cachedRendition(paths, docx))
        assertFalse(paths.renditionFile(docx.name).exists())
    }

    @Test
    fun renditionManager_reusesRenditionWhileSourceIsUnchanged() {
        val paths = libraryPaths("lib-reuse")
        val docx = Fixtures.copy(Fixtures.MINIMAL_DOCX, paths.root)
        val manager = DocxRenditionManager(Fixtures.targetContext)

        val rendition = runBlocking { manager.ensureRendition(paths, docx) }
        assertEquals(paths.renditionFile(docx.name), rendition)
        assertTrue(paths.renditionMetaFile(docx.name).isFile)
        PlatformPdf(rendition).use { assertEquals(1, it.pageCount) }
        assertEquals(rendition, manager.cachedRendition(paths, docx))
        assertFalse(manager.isSourceChanged(paths, docx))

        // Mark the rendition so a reconversion would be visible: reuse must hand back these exact bytes.
        val marker = rendition.readBytes() + "\n% reuse-marker\n".toByteArray()
        rendition.writeBytes(marker)
        val again = runBlocking { manager.ensureRendition(paths, docx) }
        assertEquals(rendition, again)
        assertTrue("unchanged source: the existing rendition is reused, not reconverted", again.readBytes().contentEquals(marker))
    }

    @Test
    fun renditionManager_reconvertsWhenSourceChanges_andReportsIt() {
        val paths = libraryPaths("lib-changed")
        val docx = Fixtures.copy(Fixtures.MINIMAL_DOCX, paths.root, asName = "Notes.docx")
        val manager = DocxRenditionManager(Fixtures.targetContext)
        val rendition = runBlocking { manager.ensureRendition(paths, docx) }
        val marker = rendition.readBytes() + "\n% stale-marker\n".toByteArray()
        rendition.writeBytes(marker)

        // The user replaces the document with a different one under the same name.
        Fixtures.copy(Fixtures.STUDY_GUIDE_DOCX, paths.root, asName = "Notes.docx")
        assertNull("a stale rendition is not offered", manager.cachedRendition(paths, docx))
        assertTrue("stale rendition = source changed", manager.isSourceChanged(paths, docx))

        val fresh = runBlocking { manager.ensureRendition(paths, docx) }
        assertNotEquals("reconverted", marker.size, fresh.length().toInt())
        PlatformPdf(fresh).use { assertEquals(STUDY_GUIDE_PAGES, it.pageCount) }
        assertNotNull(manager.cachedRendition(paths, docx))
        assertTrue("regenerated for another source: still reported until acknowledged", manager.isSourceChanged(paths, docx))
        runBlocking { manager.acknowledgeSourceChange(paths, docx.name) }
        assertFalse(manager.isSourceChanged(paths, docx))
    }

    @Test
    fun renditionManager_missingSource_raisesDocxConversionException() {
        val paths = libraryPaths("lib-missing")
        val manager = DocxRenditionManager(Fixtures.targetContext)
        val e = runCatching { runBlocking { manager.ensureRendition(paths, File(paths.root, "Gone.docx")) } }.exceptionOrNull()
        assertTrue("expected DocxConversionException, got $e", e is DocxConversionException)
    }

    private fun libraryPaths(name: String): LibraryPaths {
        val paths = LibraryPaths(Fixtures.scratchDir(name), isPublic = false)
        assertTrue(paths.ensureDirs())
        return paths
    }

    /** Text of every page, extracted with PdfBox. */
    private fun pageTexts(pdf: File): List<String> = PDDocument.load(pdf).use { doc ->
        (1..doc.numberOfPages).map { n ->
            PDFTextStripper().apply {
                startPage = n
                endPage = n
            }.getText(doc)
        }
    }

    /** The fixture picture's gradient: red 40..199, green 90..169, blue 200 (antialiasing aside). */
    private fun isPictureColour(c: Int): Boolean =
        android.graphics.Color.blue(c) in 190..210 &&
            android.graphics.Color.green(c) in 80..180 &&
            android.graphics.Color.red(c) in 30..210

    private companion object {
        const val TAG = "DocxDeviceTest"

        /**
         * Pages of the converted study guide (A4, 1-inch margins): page 1 = title … table, then the
         * 120 pt picture and its "Figure 1" caption (table rows use Word's default cell padding:
         * none above and below); the explicit page break then starts "2. Cell division" on page 2;
         * its 14 long paragraphs and the "Notes" footnote section end on page 3.
         */
        const val STUDY_GUIDE_PAGES = 3
    }
}
