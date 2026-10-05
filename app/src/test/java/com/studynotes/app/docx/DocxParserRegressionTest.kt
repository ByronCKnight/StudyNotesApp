package com.studynotes.app.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CancellationException

/** Regression tests for review findings docx-2 .. docx-13 (parser side). */
class DocxParserRegressionTest {

    // ------------------------------------------------------------------ helpers

    private fun paragraphs(blocks: List<DocxBlock>): List<DocxParagraph> = blocks.flatMap { block ->
        when (block) {
            is DocxParagraph -> listOf(block)
            is DocxTable -> block.rows.flatMap { row -> row.cells.flatMap { paragraphs(it.blocks) } }
        }
    }

    private fun DocxParagraph.text(): String = items.joinToString("") {
        when (it) {
            is DocxInline.Text -> it.text
            is DocxInline.Tab -> "\t"
            is DocxInline.NoteRef -> it.label
            else -> ""
        }
    }

    private fun DocxDocument.paragraph(text: String): DocxParagraph =
        paragraphs(blocks).firstOrNull { it.text() == text } ?: throw AssertionError("No paragraph \"$text\"")

    private fun parse(
        body: String,
        styles: String? = null,
        numbering: String? = null,
        extra: Map<String, String> = emptyMap(),
        checkpoint: () -> Unit = {},
    ): DocxDocument {
        val map = HashMap<String, String>(extra)
        map["word/document.xml"] = """<?xml version="1.0" encoding="UTF-8"?><w:document $NS><w:body>$body</w:body></w:document>"""
        styles?.let { map["word/styles.xml"] = """<w:styles $NS>$it</w:styles>""" }
        numbering?.let { map["word/numbering.xml"] = """<w:numbering $NS>$it</w:numbering>""" }
        return DocxParser.parse(DocxParts { name -> map[name]?.byteInputStream() }, checkpoint)
    }

    private fun para(text: String, pPr: String = "") = """<w:p><w:pPr>$pPr</w:pPr><w:r><w:t>$text</w:t></w:r></w:p>"""

    // ------------------------------------------------------------------ docx-2 / docx-12: sections

    @Test
    fun sectionBreak_typeComesFromTheFollowingSection() {
        // Word writes a "Continuous" break as w:type on the *following* sectPr; the ending one is untyped.
        val continuous = parse(
            para("one", "<w:sectPr/>") + para("two") +
                """<w:sectPr><w:type w:val="continuous"/></w:sectPr>""",
        )
        assertFalse(continuous.paragraph("one").sectionBreakAfter)

        // The reverse: the ending section is continuous, but the next one starts on a new page.
        val nextPage = parse(
            para("one", """<w:sectPr><w:type w:val="continuous"/></w:sectPr>""") + para("two") +
                """<w:sectPr><w:type w:val="nextPage"/></w:sectPr>""",
        )
        assertTrue(nextPage.paragraph("one").sectionBreakAfter)
    }

    @Test
    fun sectionBreak_middleSectionUsesNextParagraphSectPr() {
        val doc = parse(
            para("a", "<w:sectPr/>") +
                para("b", """<w:sectPr><w:type w:val="continuous"/></w:sectPr>""") +
                para("c") + "<w:sectPr/>",
        )
        // a → b's section is continuous; b → body section (untyped = next page).
        assertFalse(doc.paragraph("a").sectionBreakAfter)
        assertTrue(doc.paragraph("b").sectionBreakAfter)
        assertFalse(doc.paragraph("c").sectionBreakAfter)
    }

    @Test
    fun sections_keepEachSectionsPageSetup() {
        val doc = parse(
            para("portrait") +
                para("end of portrait", """<w:sectPr><w:pgSz w:w="12240" w:h="15840"/></w:sectPr>""") +
                para("landscape appendix") +
                """<w:sectPr><w:pgSz w:w="15840" w:h="12240"/></w:sectPr>""",
        )
        assertEquals(2, doc.sections.size)
        assertEquals(0, doc.sections[0].firstBlock)
        assertEquals(612f, doc.sections[0].page.widthPt, 0.001f)
        assertEquals(792f, doc.sections[0].page.heightPt, 0.001f)
        assertEquals(2, doc.sections[1].firstBlock)
        assertEquals(792f, doc.sections[1].page.widthPt, 0.001f)
        // The final section's setup stays the document page (used for the notes).
        assertEquals(doc.sections[1].page, doc.page)
    }

    @Test
    fun sections_sectPrInsideCellsIsIgnored() {
        val doc = parse(
            """<w:tbl><w:tr><w:tc>${para("cell", "<w:sectPr/>")}</w:tc></w:tr></w:tbl>""" + para("after"),
        )
        assertEquals(1, doc.sections.size)
        assertFalse(doc.paragraph("cell").sectionBreakAfter)
        assertEquals(DocxPageSetup.DEFAULT, doc.page)
    }

    // ------------------------------------------------------------------ docx-4: table style formatting

    @Test
    fun tableStyle_paragraphPropertiesApplyToCellParagraphs() {
        val styles = """
            <w:docDefaults>
              <w:rPrDefault><w:rPr><w:sz w:val="22"/></w:rPr></w:rPrDefault>
              <w:pPrDefault><w:pPr><w:spacing w:after="160" w:line="259" w:lineRule="auto"/></w:pPr></w:pPrDefault>
            </w:docDefaults>
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"/>
            <w:style w:type="paragraph" w:styleId="Spaced"><w:pPr><w:spacing w:after="240"/></w:pPr></w:style>
            <w:style w:type="table" w:styleId="TableGrid">
              <w:pPr><w:spacing w:after="0" w:line="240" w:lineRule="auto"/></w:pPr>
              <w:rPr><w:color w:val="FF0000"/></w:rPr>
            </w:style>
        """
        val doc = parse(
            """<w:tbl><w:tblPr><w:tblStyle w:val="TableGrid"/></w:tblPr><w:tblGrid><w:gridCol w:w="2000"/></w:tblGrid>
                 <w:tr><w:tc>${para("cell")}${para("styled cell", """<w:pStyle w:val="Spaced"/>""")}</w:tc></w:tr></w:tbl>""" +
                para("body"),
            styles,
        )
        val cell = doc.paragraph("cell")
        assertEquals(0f, cell.props.spaceAfterPt, 0.001f)
        assertEquals(1f, cell.props.lineValue, 0.001f)
        assertEquals(OoxmlValues.opaque(0xFF0000), (cell.items.single() as DocxInline.Text).run.color)
        // The paragraph style sits above the table style.
        assertEquals(12f, doc.paragraph("styled cell").props.spaceAfterPt, 0.001f)

        // Outside the table the document defaults apply again.
        val body = doc.paragraph("body")
        assertEquals(8f, body.props.spaceAfterPt, 0.001f)
        assertEquals(259f / 240f, body.props.lineValue, 0.001f)
        assertNull((body.items.single() as DocxInline.Text).run.color)
    }

    @Test
    fun tableStyle_defaultTableStyleAndNestedTables() {
        val styles = """
            <w:style w:type="table" w:default="1" w:styleId="Plain"><w:pPr><w:spacing w:after="20"/></w:pPr></w:style>
            <w:style w:type="table" w:styleId="Inner"><w:pPr><w:spacing w:after="40"/></w:pPr></w:style>
        """
        val doc = parse(
            """<w:tbl><w:tr><w:tc>${para("outer")}
                 <w:tbl><w:tblPr><w:tblStyle w:val="Inner"/></w:tblPr><w:tr><w:tc>${para("inner")}</w:tc></w:tr></w:tbl>
                 ${para("outer again")}</w:tc></w:tr></w:tbl>""",
            styles,
        )
        assertEquals(1f, doc.paragraph("outer").props.spaceAfterPt, 0.001f)
        assertEquals(2f, doc.paragraph("inner").props.spaceAfterPt, 0.001f)
        assertEquals(1f, doc.paragraph("outer again").props.spaceAfterPt, 0.001f)
    }

    // ------------------------------------------------------------------ docx-5: tab alignment

    @Test
    fun tabStops_keepAlignmentAndLaterDefinitionsWin() {
        val styles = """
            <w:style w:type="paragraph" w:styleId="TOC1"><w:pPr><w:tabs>
              <w:tab w:val="right" w:leader="dot" w:pos="9350"/><w:tab w:val="left" w:pos="720"/>
            </w:tabs></w:pPr></w:style>
        """
        val doc = parse(
            para("toc", """<w:pStyle w:val="TOC1"/><w:tabs><w:tab w:val="clear" w:pos="720"/><w:tab w:val="center" w:pos="4680"/><w:tab w:val="decimal" w:pos="7200"/><w:tab w:val="bar" w:pos="100"/></w:tabs>"""),
            styles,
        )
        assertEquals(
            listOf(
                DocxTabStop(234f, DocxTabAlign.CENTER),
                DocxTabStop(360f, DocxTabAlign.DECIMAL),
                DocxTabStop(467.5f, DocxTabAlign.RIGHT),
            ),
            doc.paragraph("toc").props.tabStops,
        )

        val redefined = parse(para("x", """<w:tabs><w:tab w:val="right" w:pos="720"/><w:tab w:val="left" w:pos="720"/></w:tabs>"""))
        assertEquals(listOf(DocxTabStop(36f, DocxTabAlign.LEFT)), redefined.paragraph("x").props.tabStops)
    }

    // ------------------------------------------------------------------ docx-6: list styles

    @Test
    fun numbering_numStyleLinkResolvesThroughTheListStyle() {
        val styles = """
            <w:style w:type="numbering" w:styleId="ThesisList"><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style>
        """
        val numbering = """
            <w:abstractNum w:abstractNumId="0"><w:styleLink w:val="ThesisList"/>
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
              <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%1.%2"/></w:lvl>
            </w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:numStyleLink w:val="ThesisList"/></w:abstractNum>
            <w:abstractNum w:abstractNumId="2"><w:numStyleLink w:val="Missing"/></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
            <w:num w:numId="3"><w:abstractNumId w:val="2"/></w:num>
        """
        fun item(text: String, numId: Int, ilvl: Int) =
            para(text, """<w:numPr><w:ilvl w:val="$ilvl"/><w:numId w:val="$numId"/></w:numPr>""")
        val doc = parse(item("a", 2, 0) + item("b", 2, 1) + item("c", 2, 0) + item("d", 3, 0), styles, numbering)
        assertEquals("1.", doc.paragraph("a").label?.text)
        assertEquals("1.a", doc.paragraph("b").label?.text)
        assertEquals("2.", doc.paragraph("c").label?.text)
        assertNull(doc.paragraph("d").label)
    }

    @Test
    fun numbering_styleLinkCyclesDoNotHang() {
        val styles = """
            <w:style w:type="numbering" w:styleId="A"><w:pPr><w:numPr><w:numId w:val="2"/></w:numPr></w:pPr></w:style>
            <w:style w:type="numbering" w:styleId="B"><w:pPr><w:numPr><w:numId w:val="1"/></w:numPr></w:pPr></w:style>
        """
        val numbering = """
            <w:abstractNum w:abstractNumId="0"><w:numStyleLink w:val="A"/></w:abstractNum>
            <w:abstractNum w:abstractNumId="1"><w:numStyleLink w:val="B"/></w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="1"/></w:num>
        """
        val doc = parse(para("x", """<w:numPr><w:ilvl w:val="0"/><w:numId w:val="1"/></w:numPr>"""), styles, numbering)
        assertNull(doc.paragraph("x").label)
    }

    // ------------------------------------------------------------------ docx-7: picture crop

    @Test
    fun pictures_cropIsParsedFromDrawingMlAndVml() {
        val rels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image1.png"/>
            </Relationships>"""
        val doc = parse(
            """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="1270000" cy="635000"/><a:graphic><a:graphicData><pic:pic>
                 <pic:blipFill><a:blip r:embed="rId5"/><a:srcRect l="10000" t="25000" r="-5000"/></pic:blipFill>
               </pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>
               <w:p><w:r><w:pict><v:shape style="width:100pt;height:50pt"><v:imagedata r:id="rId5" cropleft="16384f" cropbottom="0.5"/></v:shape></w:pict></w:r></w:p>
               <w:p><w:r><w:drawing><wp:inline><wp:extent cx="12700" cy="12700"/><a:blip r:embed="rId5"/><a:srcRect l="60000" r="60000"/></wp:inline></w:drawing></w:r></w:p>""",
            extra = mapOf("word/_rels/document.xml.rels" to rels),
        )
        val images = paragraphs(doc.blocks).flatMap { it.items }.filterIsInstance<DocxInline.Image>()
        assertEquals(3, images.size)
        val dml = images[0]
        assertEquals("word/media/image1.png", dml.entryName)
        assertEquals(100f, dml.widthPt, 0.001f)
        assertEquals(0.1f, dml.cropLeft, 0.0001f)
        assertEquals(0.25f, dml.cropTop, 0.0001f)
        assertEquals(-0.05f, dml.cropRight, 0.0001f)
        assertEquals(0f, dml.cropBottom, 0.0001f)

        val vml = images[1]
        assertEquals(0.25f, vml.cropLeft, 0.0001f)
        assertEquals(0.5f, vml.cropBottom, 0.0001f)

        // A crop that leaves nothing visible is treated as broken and ignored.
        assertFalse(images[2].isCropped)
    }

    @Test
    fun cropSanitising() {
        assertEquals(0.1f to 0.2f, DocxParser.sanitizeCrop(0.1f, 0.2f))
        assertEquals(0f to 0f, DocxParser.sanitizeCrop(0.7f, 0.3f))
        assertEquals(-1f to 0.5f, DocxParser.sanitizeCrop(-7f, 0.5f))
        assertEquals(0f to 0f, DocxParser.sanitizeCrop(Float.NaN, Float.POSITIVE_INFINITY))
        assertEquals(0.125f, OoxmlValues.cropFraction("8192f", drawingMl = false)!!, 0.0001f)
        assertEquals(0.125f, OoxmlValues.cropFraction("12500", drawingMl = true)!!, 0.0001f)
        assertNull(OoxmlValues.cropFraction("NaN", drawingMl = false))
    }

    // ------------------------------------------------------------------ docx-9: cancellation and limits

    @Test
    fun parse_callsCheckpointAndPropagatesCancellation() {
        val body = (1..2000).joinToString("") { para("p$it") }
        var calls = 0
        parse(body, checkpoint = { calls++ })
        assertTrue("checkpoint called $calls times", calls >= 5)

        var seen = 0
        try {
            parse(body, styles = """<w:style w:type="paragraph" w:styleId="S"/>""", checkpoint = {
                if (++seen == 3) throw CancellationException("cancelled")
            })
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun parse_cancellationWhileReadingStylesIsNotSwallowed() {
        val styles = (1..600).joinToString("") { """<w:style w:type="paragraph" w:styleId="S$it"/>""" }
        try {
            parse(para("x"), styles = styles, checkpoint = { throw CancellationException("cancelled") })
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun oversizedPart_failsAsTooLargeEvenWhenOptional() {
        val document = """<w:document $NS><w:body>${para("x")}</w:body></w:document>"""
        val parts = DocxParts { name ->
            when (name) {
                "word/document.xml" -> document.byteInputStream()
                // The limit lies well past the parser's encoding sniffing and first buffer fills.
                "word/styles.xml" -> SizeLimitedInputStream(endless("<w:styles $NS>", "<w:style w:styleId=\"s\"/>"), 256L * 1024)
                else -> null
            }
        }
        try {
            DocxParser.parse(parts)
            fail("Expected DocxConversionException")
        } catch (e: DocxConversionException) {
            assertEquals(DocxConverter.TOO_LARGE_MESSAGE, e.message)
        }
    }

    @Test
    fun sizeLimitedInputStream_countsAllReads() {
        val stream = SizeLimitedInputStream(ByteArray(100).inputStream(), 10)
        assertEquals(0, stream.read())
        assertEquals(9, stream.read(ByteArray(9)))
        try {
            stream.read()
            fail("Expected DocxConversionException")
        } catch (e: DocxConversionException) {
            assertEquals(DocxConverter.TOO_LARGE_MESSAGE, e.message)
        }
    }

    @Test
    fun manyIdenticalRuns_mergeIntoOneTextItem() {
        val runs = (1..5000).joinToString("") { "<w:r><w:t>ab</w:t></w:r>" }
        val p = paragraphs(parse("<w:p>$runs<w:r><w:tab/></w:r><w:r><w:t>c</w:t></w:r></w:p>").blocks).single()
        assertEquals(3, p.items.size)
        assertEquals(10_000, (p.items[0] as DocxInline.Text).text.length)
        assertEquals("c", (p.items[2] as DocxInline.Text).text)
    }

    // ------------------------------------------------------------------ docx-10: line spacing values

    @Test
    fun lineSpacing_nonFiniteAndHugeValuesAreSafe() {
        val doc = parse(
            para("nan", """<w:spacing w:line="NaN" w:lineRule="exact"/>""") +
                para("inf", """<w:spacing w:line="Infinity" w:lineRule="auto"/>""") +
                para("huge", """<w:spacing w:line="1e30" w:lineRule="atLeast"/>"""),
        )
        val nan = doc.paragraph("nan").props
        assertEquals(DocxLineRule.AUTO, nan.lineRule)
        assertEquals(1f, nan.lineValue, 0.001f)
        assertEquals(1f, doc.paragraph("inf").props.lineValue, 0.001f)
        val huge = doc.paragraph("huge").props
        assertEquals(DocxLineRule.AT_LEAST, huge.lineRule)
        assertTrue(huge.lineValue.isFinite() && huge.lineValue <= 1584f)
    }

    // ------------------------------------------------------------------ docx-11: pictures in notes

    @Test
    fun notes_picturesResolveAgainstTheNotesPartRelationships() {
        val relNs = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        val pkgNs = "http://schemas.openxmlformats.org/package/2006/relationships"
        val docRels = """<Relationships xmlns="$pkgNs">
            <Relationship Id="rId1" Type="$relNs/image" Target="media/body.png"/>
            <Relationship Id="rId9" Type="$relNs/footnotes" Target="footnotes.xml"/>
            </Relationships>"""
        val noteRels = """<Relationships xmlns="$pkgNs">
            <Relationship Id="rId1" Type="$relNs/image" Target="media/note.png"/>
            </Relationships>"""
        val footnotes = """<w:footnotes $NS><w:footnote w:id="1"><w:p><w:r><w:t>See </w:t></w:r>
            <w:r><w:drawing><wp:inline><wp:extent cx="12700" cy="12700"/><a:blip r:embed="rId1"/></wp:inline></w:drawing></w:r></w:p></w:footnote></w:footnotes>"""
        val doc = parse(
            """<w:p><w:r><w:drawing><wp:inline><wp:extent cx="12700" cy="12700"/><a:blip r:embed="rId1"/></wp:inline></w:drawing></w:r>
               <w:r><w:footnoteReference w:id="1"/></w:r></w:p>""",
            extra = mapOf(
                "word/_rels/document.xml.rels" to docRels,
                "word/footnotes.xml" to footnotes,
                "word/_rels/footnotes.xml.rels" to noteRels,
            ),
        )
        val bodyImage = paragraphs(doc.blocks).flatMap { it.items }.filterIsInstance<DocxInline.Image>().single()
        assertEquals("word/media/body.png", bodyImage.entryName)
        val noteImage = paragraphs(doc.notes.single().blocks).flatMap { it.items }.filterIsInstance<DocxInline.Image>().single()
        assertEquals("word/media/note.png", noteImage.entryName)
    }

    // ------------------------------------------------------------------ docx-13: font classification

    @Test
    fun fontKind_gothicFamiliesAreSans() {
        assertEquals(DocxFontKind.SANS, DocxFontKind.of("Century Gothic"))
        assertEquals(DocxFontKind.SANS, DocxFontKind.of("Franklin Gothic Book"))
        assertEquals(DocxFontKind.SANS, DocxFontKind.of("HK Grotesk"))
        assertEquals(DocxFontKind.SERIF, DocxFontKind.of("Century Schoolbook"))
        assertEquals(DocxFontKind.SERIF, DocxFontKind.of("Times New Roman"))
        assertEquals(DocxFontKind.MONO, DocxFontKind.of("Courier New"))
        assertEquals(DocxFontKind.SANS, DocxFontKind.of(null))
    }

    // ------------------------------------------------------------------ main part lookup

    @Test
    fun findMainDocumentPart_followsRootRelationship() {
        val rootRels = """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="/word/document2.xml"/>
            </Relationships>"""
        val withRels = mapOf("_rels/.rels" to rootRels, "word/document2.xml" to "<w:document $NS/>")
        assertEquals("word/document2.xml", DocxParser.findMainDocumentPart(DocxParts { withRels[it]?.byteInputStream() }))
        val plain = mapOf("word/document.xml" to "<w:document $NS/>")
        assertEquals("word/document.xml", DocxParser.findMainDocumentPart(DocxParts { plain[it]?.byteInputStream() }))
        assertNull(DocxParser.findMainDocumentPart(DocxParts { null }))
    }

    /** A stream that starts with [head] and then repeats [unit] forever. */
    private fun endless(head: String, unit: String): InputStream = object : InputStream() {
        private val headBytes = head.toByteArray()
        private val unitBytes = unit.toByteArray()
        private var pos = 0L

        override fun read(): Int {
            val i = pos++
            return if (i < headBytes.size) headBytes[i.toInt()].toInt() and 0xFF
            else unitBytes[((i - headBytes.size) % unitBytes.size).toInt()].toInt() and 0xFF
        }
    }

    private companion object {
        const val NS = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" """ +
            """xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture" """ +
            """xmlns:v="urn:schemas-microsoft-com:vml" """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006""""
    }
}
