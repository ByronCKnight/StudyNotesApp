package com.studynotes.app.export

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfPageInspectionTest {

    private fun ops(content: String) = content.split(' ').filter { it.isNotEmpty() }.asSequence()

    private fun stream(content: String): COSStream = COSStream().apply {
        createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
    }

    private fun pageWithContents(vararg streams: String): PDPage {
        val page = PDPage()
        val contents = if (streams.size == 1) {
            stream(streams[0])
        } else {
            COSArray().apply { streams.forEach { add(stream(it)) } }
        }
        page.cosObject.setItem(COSName.CONTENTS, contents)
        return page
    }

    // ---- q/Q depth (export-6)

    @Test
    fun balancedContentLeavesNothingOpen() {
        assertEquals(0, PdfPageInspection.unclosedSaveDepth(ops("q 1 0 0 1 5 5 cm q Q Q")))
        assertEquals(0, PdfPageInspection.unclosedSaveDepth(emptySequence()))
    }

    @Test
    fun countsSavesLeftOpen() {
        assertEquals(1, PdfPageInspection.unclosedSaveDepth(ops("0.24 0 0 0.24 0 0 cm q /Im0 Do")))
        assertEquals(2, PdfPageInspection.unclosedSaveDepth(ops("q q q Q")))
    }

    @Test
    fun extraRestoresAreIgnoredLikeViewersDo() {
        assertEquals(1, PdfPageInspection.unclosedSaveDepth(ops("Q Q q")))
        assertEquals(0, PdfPageInspection.unclosedSaveDepth(ops("q Q Q")))
    }

    @Test
    fun parsesThePagesRealContentStream() {
        val page = pageWithContents("0.24 0 0 0.24 0 0 cm q /Im0 Do")
        assertEquals(1, PdfPageInspection.unclosedSaveDepth(page))
    }

    @Test
    fun parsesEveryStreamOfAContentsArray() {
        val page = pageWithContents("q 2 0 0 2 0 0 cm q", "0 0 m 1 1 l S Q", "q")
        assertEquals(2, PdfPageInspection.unclosedSaveDepth(page))
    }

    @Test
    fun operatorNamesInsideStringsDoNotCount() {
        val page = pageWithContents("BT /F1 12 Tf (q q q) Tj ET")
        assertEquals(0, PdfPageInspection.unclosedSaveDepth(page))
    }

    @Test
    fun pageWithoutContentIsBalanced() {
        assertEquals(0, PdfPageInspection.unclosedSaveDepth(PDPage()))
    }

    // ---- raw /Rotate (export-7)

    @Test
    fun keepsRotationsThatAreNotMultiplesOf90() {
        val page = COSDictionary().apply { setItem(COSName.ROTATE, COSInteger.get(100)) }
        assertEquals(100, PdfPageInspection.rawRotation(page))
        // PDFium truncates 100 to a quarter turn, so the export must map it like /Rotate 90.
        val crop = floatArrayOf(0f, 0f, 612f, 792f)
        assertEquals(
            PdfSpaceTransform.displayToPdf(90, crop[0], crop[1], crop[2], crop[3]),
            PdfSpaceTransform.displayToPdf(PdfPageInspection.rawRotation(page), crop[0], crop[1], crop[2], crop[3]),
        )
    }

    @Test
    fun inheritsRotationFromThePageTree() {
        val pages = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.PAGES)
            setItem(COSName.ROTATE, COSInteger.get(-100))
        }
        val page = COSDictionary().apply {
            setItem(COSName.TYPE, COSName.PAGE)
            setItem(COSName.PARENT, pages)
        }
        assertEquals(-100, PdfPageInspection.rawRotation(page))
        assertEquals(270, PdfSpaceTransform.normalizeRotation(PdfPageInspection.rawRotation(page)))
    }

    @Test
    fun realNumberRotationIsTruncated() {
        val page = COSDictionary().apply { setItem(COSName.ROTATE, COSFloat(270.9f)) }
        assertEquals(270, PdfPageInspection.rawRotation(page))
    }

    @Test
    fun missingOrInvalidRotationIsZero() {
        assertEquals(0, PdfPageInspection.rawRotation(COSDictionary()))
        val page = COSDictionary().apply { setItem(COSName.ROTATE, COSName.getPDFName("Ninety")) }
        assertEquals(0, PdfPageInspection.rawRotation(page))
    }
}
