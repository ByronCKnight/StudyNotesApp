package com.studynotes.app.export

import com.tom_roush.pdfbox.contentstream.operator.Operator
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageTree

/** Facts about an existing page that the vector export needs before drawing on it. Blocking. */
internal object PdfPageInspection {

    /**
     * The page's raw, inherited /Rotate value (0 when absent or not a number). Unlike
     * PDPage.getRotation, which returns 0 for anything that is not a multiple of 90, this keeps
     * e.g. 100, which PDFium — and so the editor's page space — reads as a quarter turn; pass it to
     * [PdfSpaceTransform.displayToPdf], which normalises it the same way PDFium does.
     */
    fun rawRotation(page: COSDictionary): Int =
        (PDPageTree.getInheritableAttribute(page, COSName.ROTATE) as? COSNumber)?.intValue() ?: 0

    /**
     * How many 'q' (save graphics state) operators [page]'s content leaves unclosed at its end. The
     * exporter must emit that many extra 'Q' so that its annotations start from the page's initial
     * graphics state: PdfBox's resetContext wraps the old content in a single q/Q pair, so an
     * unbalanced 'q' inside it would otherwise leave its CTM or clip applied to everything appended.
     * Unreadable content counts as balanced (0); the content stream writer will then fail or cope.
     */
    fun unclosedSaveDepth(page: PDPage): Int = try {
        val parser = PDFStreamParser(page)
        unclosedSaveDepth(generateSequence { parser.parseNextToken() }.mapNotNull { (it as? Operator)?.name })
    } catch (e: Exception) {
        0
    }

    /**
     * The 'q' nesting depth left after running [operators] in order. A 'Q' with nothing to restore
     * is ignored, as viewers do.
     */
    fun unclosedSaveDepth(operators: Sequence<String>): Int {
        var depth = 0
        for (op in operators) {
            when (op) {
                "q" -> depth++
                "Q" -> if (depth > 0) depth--
            }
        }
        return depth
    }
}
