package com.studynotes.app.export

import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Stroke
import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.PDStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Runs the vector export on real PdfBox documents (pen strokes only: notes need android.graphics)
 * and checks the operators it appends.
 */
class VectorPdfWriterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val penStroke = Stroke("s", InkTool.PEN, 0xFF1A73E8.toInt(), 2f, floatArrayOf(10f, 10f, 100f, 100f))
    private val annotations = DocumentAnnotations(pages = mapOf(0 to PageAnnotations(strokes = listOf(penStroke))))

    /** A one-page 612 x 792 PDF with [content] as its only content stream and an optional /Rotate. */
    private fun sourcePdf(content: String, rotate: Int? = null): File {
        val file = tmp.newFile("source.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(612f, 792f))
            if (rotate != null) page.cosObject.setItem(COSName.ROTATE, COSInteger.get(rotate.toLong()))
            val stream = PDStream(doc)
            stream.createOutputStream().use { it.write(content.toByteArray(Charsets.ISO_8859_1)) }
            page.setContents(stream)
            doc.addPage(page)
            doc.save(file)
        }
        return file
    }

    private fun export(source: File): File {
        val out = File(tmp.root, "out.pdf")
        runBlocking { VectorPdfWriter(tmp.newFolder()).write(source, annotations, out, includeNotes = true) }
        return out
    }

    private fun tokens(stream: COSStream): List<Any> {
        val parser = PDFStreamParser(stream.createInputStream().use { it.readBytes() })
        return generateSequence { parser.parseNextToken() }.toList()
    }

    private fun operatorNames(tokens: List<Any>) = tokens.mapNotNull { (it as? Operator)?.name }

    /** The streams of the exported first page: everything before the appended one, and that one. */
    private fun exportedStreams(out: File, check: (before: List<COSStream>, appended: COSStream) -> Unit) {
        PDDocument.load(out).use { doc ->
            val contents = doc.getPage(0).cosObject.getDictionaryObject(COSName.CONTENTS) as COSArray
            val streams = (0 until contents.size()).map { contents.getObject(it) as COSStream }
            check(streams.dropLast(1), streams.last())
        }
    }

    /** The six operands of the first `cm` in [tokens]. */
    private fun firstMatrix(tokens: List<Any>): List<Float> {
        val cm = tokens.indexOfFirst { it is Operator && it.name == "cm" }
        return tokens.subList(cm - 6, cm).map { (it as COSNumber).floatValue() }
    }

    @Test
    fun annotationsStartFromTheInitialStateWhenTheContentLeavesASaveOpen() {
        // A scanner-style page: an outer scale, then an inner q that is never closed.
        val out = export(sourcePdf("0.24 0 0 0.24 0 0 cm q 0 0 m 10 10 l S"))
        exportedStreams(out) { before, appended ->
            val openBefore = PdfPageInspection.unclosedSaveDepth(before.flatMap { operatorNames(tokens(it)) }.asSequence())
            assertEquals(2, openBefore) // resetContext's own q plus the page's unclosed one
            val ops = operatorNames(tokens(appended))
            assertEquals(listOf("Q", "Q", "cm"), ops.take(3))
        }
    }

    @Test
    fun balancedContentGetsOnlyTheResetContextRestore() {
        val out = export(sourcePdf("q 0.24 0 0 0.24 0 0 cm 0 0 m 10 10 l S Q"))
        exportedStreams(out) { _, appended ->
            assertEquals(listOf("Q", "cm"), operatorNames(tokens(appended)).take(2))
        }
    }

    @Test
    fun rotationThatIsNotAMultipleOf90IsMappedLikePdfium() {
        val out = export(sourcePdf("0 0 m 10 10 l S", rotate = 100))
        exportedStreams(out) { _, appended ->
            // /Rotate 90 over the crop box (0, 0, 612, 792): x' = y, y' = x.
            assertEquals(listOf(0f, 1f, 1f, 0f, 0f, 0f), firstMatrix(tokens(appended)))
        }
    }

    @Test
    fun unrotatedPageKeepsTheFlipMatrix() {
        val out = export(sourcePdf("0 0 m 10 10 l S"))
        exportedStreams(out) { _, appended ->
            assertEquals(listOf(1f, 0f, 0f, -1f, 0f, 792f), firstMatrix(tokens(appended)))
        }
    }
}
