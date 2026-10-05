package com.studynotes.app.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DocxParserTest {

    // ------------------------------------------------------------------ helpers

    private fun fixture(name: String): DocxDocument = DocxParser.parse(File("../testdata/$name"))

    /** All paragraphs, including those nested in table cells, in document order. */
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

    private fun DocxDocument.paragraph(startsWith: String): DocxParagraph =
        paragraphs(blocks).firstOrNull { it.text().startsWith(startsWith) }
            ?: throw AssertionError("No paragraph starting with \"$startsWith\"")

    private fun DocxParagraph.run(text: String): DocxRunProps =
        items.filterIsInstance<DocxInline.Text>().firstOrNull { it.text == text }?.run
            ?: throw AssertionError("No run \"$text\" in \"${text()}\"")

    private fun parts(
        body: String,
        styles: String? = null,
        numbering: String? = null,
    ): DocxParts {
        val map = HashMap<String, String>()
        map["word/document.xml"] = """<?xml version="1.0" encoding="UTF-8"?><w:document $NS><w:body>$body</w:body></w:document>"""
        styles?.let { map["word/styles.xml"] = """<w:styles $NS>$it</w:styles>""" }
        numbering?.let { map["word/numbering.xml"] = """<w:numbering $NS>$it</w:numbering>""" }
        return DocxParts { name -> map[name]?.byteInputStream() }
    }

    private fun parse(body: String, styles: String? = null, numbering: String? = null): DocxDocument =
        DocxParser.parse(parts(body, styles, numbering))

    private fun color(rgb: Int) = OoxmlValues.opaque(rgb)

    // ------------------------------------------------------------------ fixtures

    @Test
    fun studyGuide_pageSetupIsA4WithInchMargins() {
        val page = fixture("Cell Biology Study Guide.docx").page
        assertEquals(595.3f, page.widthPt, 0.01f)
        assertEquals(841.9f, page.heightPt, 0.01f)
        assertEquals(72f, page.marginTopPt, 0.001f)
        assertEquals(72f, page.marginRightPt, 0.001f)
        assertEquals(72f, page.marginBottomPt, 0.001f)
        assertEquals(72f, page.marginLeftPt, 0.001f)
    }

    @Test
    fun studyGuide_headingFormattingComesFromStyles() {
        val doc = fixture("Cell Biology Study Guide.docx")
        val title = doc.paragraph("Cell Biology")
        assertEquals("Title", title.props.styleId)
        assertEquals(26f, title.run("Cell Biology — Study Guide").sizePt, 0.001f)
        assertEquals(color(0x1F3864), title.run("Cell Biology — Study Guide").color)

        val h1 = doc.paragraph("1. The cell membrane")
        assertEquals("Heading1", h1.props.styleId)
        assertTrue(h1.props.keepNext)
        assertEquals(12f, h1.props.spaceBeforePt, 0.001f)
        val h1Run = h1.run("1. The cell membrane")
        assertTrue(h1Run.bold)
        assertEquals(16f, h1Run.sizePt, 0.001f)
        assertEquals(color(0x2F5496), h1Run.color)

        // Heading2 is based on Heading1: inherits bold + colour, overrides the size.
        val h2 = doc.paragraph("2.1 Mitosis").run("2.1 Mitosis")
        assertTrue(h2.bold)
        assertEquals(13f, h2.sizePt, 0.001f)
        assertEquals(color(0x2F5496), h2.color)
    }

    @Test
    fun studyGuide_bodyRunsAndParagraphProps() {
        val doc = fixture("Cell Biology Study Guide.docx")
        val p = doc.paragraph("The membrane is a")
        assertEquals(DocxAlignment.JUSTIFY, p.props.alignment)
        assertEquals(DocxLineRule.AUTO, p.props.lineRule)
        assertEquals(276f / 240f, p.props.lineValue, 0.001f)
        assertEquals(8f, p.props.spaceAfterPt, 0.001f)

        val plain = p.run("The membrane is a ")
        assertEquals(11f, plain.sizePt, 0.001f)
        assertEquals("Calibri", plain.fontName)
        assertFalse(plain.bold)
        assertTrue(p.run("phospholipid bilayer").bold)
        assertTrue(p.run("embedded proteins").italic)
        assertTrue(p.run("selectively permeable").underline)
        assertEquals(color(0xFFFF00), p.run("some substances").highlight)
        assertEquals(color(0xC00000), p.run("others").color)

        val scripts = p.items.filterIsInstance<DocxInline.Text>().filter { it.text == "2" }.map { it.run.vertAlign }
        assertEquals(listOf(DocxVertAlign.SUBSCRIPT, DocxVertAlign.SUPERSCRIPT), scripts)

        val ref = p.items.filterIsInstance<DocxInline.NoteRef>().single()
        assertEquals("1", ref.label)
        assertEquals(DocxVertAlign.SUPERSCRIPT, ref.run.vertAlign)

        val courier = doc.paragraph("Centred paragraph in Courier")
        assertEquals(DocxAlignment.CENTER, courier.props.alignment)
        assertEquals("Courier New", courier.run("Centred paragraph in Courier").fontName)

        val strike = doc.paragraph("Right aligned")
        assertEquals(DocxAlignment.RIGHT, strike.props.alignment)
        assertTrue(strike.run("Right aligned, 14pt, strike").strike)
        assertEquals(14f, strike.run("Right aligned, 14pt, strike").sizePt, 0.001f)

        val link = doc.paragraph("Khan Academy").run("Khan Academy: membranes")
        assertEquals(color(0x0563C1), link.color)
        assertTrue(link.underline)

        val tabbed = doc.paragraph("Term")
        assertTrue(tabbed.items.any { it is DocxInline.Tab })

        val hanging = doc.paragraph("Indented quote")
        assertEquals(36f, hanging.props.indentLeftPt, 0.001f)
        assertEquals(36f, hanging.props.indentRightPt, 0.001f)
        assertEquals(-18f, hanging.props.firstLinePt, 0.001f)
    }

    @Test
    fun studyGuide_listLabels() {
        val doc = fixture("Cell Biology Study Guide.docx")
        val bullets = listOf("Simple diffusion", "Facilitated diffusion", "Active transport").map { doc.paragraph(it) }
        bullets.forEach { assertEquals("•", it.label?.text) }
        assertEquals(36f, bullets[0].props.indentLeftPt, 0.001f)
        assertEquals(-18f, bullets[0].props.firstLinePt, 0.001f)
        assertTrue(bullets[0].props.contextualSpacing)
        assertEquals(DocxLabelSuffix.TAB, bullets[0].label?.suffix)

        val nested = doc.paragraph("Endocytosis")
        assertEquals("o", nested.label?.text)
        assertEquals(72f, nested.props.indentLeftPt, 0.001f)

        val steps = listOf("3 Na+", "ATP is hydrolysed", "Pump changes shape", "2 K+").map { doc.paragraph(it).label?.text }
        assertEquals(listOf("1.", "2.", "3.", "4."), steps)

        assertNull(doc.paragraph("Key transport mechanisms").label)
    }

    @Test
    fun studyGuide_table() {
        val doc = fixture("Cell Biology Study Guide.docx")
        val table = doc.blocks.filterIsInstance<DocxTable>().single()
        assertEquals(listOf(120f, 180f, 150f), table.gridPt)
        assertEquals(5, table.rows.size)
        val header = table.rows[0]
        assertEquals(3, header.cells.size)
        header.cells.forEach { assertEquals(color(0xDCE3F5), it.shading) }
        val headerRun = (header.cells[0].blocks.single() as DocxParagraph).run("Organelle")
        assertTrue(headerRun.bold)
        assertNull(table.rows[1].cells[0].shading)

        val last = table.rows.last()
        assertEquals(1, last.cells.size)
        assertEquals(3, last.cells[0].gridSpan)
        assertEquals(DocxAlignment.CENTER, (last.cells[0].blocks.single() as DocxParagraph).props.alignment)

        assertEquals(0.5f, table.borders.top!!.widthPt, 0.001f)
        assertTrue(table.borders.insideV!!.isVisible)
        assertEquals(color(0x000000), table.borders.insideH!!.color)
    }

    @Test
    fun studyGuide_imagePageBreakNotesAndRevisions() {
        val doc = fixture("Cell Biology Study Guide.docx")
        val all = paragraphs(doc.blocks)

        val image = all.flatMap { it.items }.filterIsInstance<DocxInline.Image>().single()
        assertEquals("word/media/image1.png", image.entryName)
        assertEquals(240f, image.widthPt, 0.001f)
        assertEquals(120f, image.heightPt, 0.001f)

        assertTrue(all.any { p -> p.items.any { it == DocxInline.PageBreak } })

        val fullText = all.joinToString("\n") { it.text() }
        assertFalse(fullText.contains("GONE"))
        assertTrue(fullText.contains("Deleted text should not appear: inserted text should appear."))

        val note = doc.notes.single()
        assertEquals("1", note.label)
        val noteText = paragraphs(note.blocks).joinToString { it.text() }
        assertTrue(noteText.contains("Singer & Nicolson, fluid mosaic model (1972)."))
        assertTrue(noteText.startsWith("1"))
    }

    @Test
    fun minimal_usesDefaults() {
        val doc = fixture("Minimal.docx")
        assertEquals(DocxPageSetup.DEFAULT, doc.page)
        val paras = paragraphs(doc.blocks)
        assertEquals(listOf("Minimal document with no styles part.", "Second paragraph."), paras.map { it.text() })
        val run = paras[0].run("Minimal document with no styles part.")
        assertEquals(DocxRunProps.DEFAULT_FONT_SIZE_PT, run.sizePt, 0.001f)
        assertFalse(run.bold)
        assertNull(paras[0].label)
        assertEquals(0f, paras[0].props.spaceAfterPt, 0.001f)
        assertTrue(doc.notes.isEmpty())
    }

    @Test
    fun corrupt_throwsFriendlyException() {
        try {
            fixture("Corrupt.docx")
            fail("Expected DocxConversionException")
        } catch (e: DocxConversionException) {
            assertEquals(DocxParser.INVALID_DOCUMENT_MESSAGE, e.message)
        }
    }

    @Test
    fun zipWithoutMainDocument_isInvalid() {
        try {
            DocxParser.parse(DocxParts { null })
            fail("Expected DocxConversionException")
        } catch (e: DocxConversionException) {
            assertEquals(DocxParser.INVALID_DOCUMENT_MESSAGE, e.message)
        }
    }

    @Test
    fun nonXmlMainDocument_isInvalid() {
        for (content in listOf("this is not xml", "<html><body>web page</body></html>")) {
            val bad = DocxParts { name -> if (name == "word/document.xml") content.byteInputStream() else null }
            try {
                DocxParser.parse(bad)
                fail("Expected DocxConversionException for $content")
            } catch (e: DocxConversionException) {
                assertEquals(DocxParser.INVALID_DOCUMENT_MESSAGE, e.message)
            }
        }
    }

    @Test
    fun truncatedMainDocument_keepsWhatWasRead() {
        val truncated = DocxParts { name ->
            if (name == "word/document.xml") "<w:document $NS><w:body><w:p><w:r><w:t>first</w:t></w:r></w:p><w:p><w:r>".byteInputStream() else null
        }
        val paras = paragraphs(DocxParser.parse(truncated).blocks)
        assertEquals("first", paras.first().text())
    }

    // ------------------------------------------------------------------ synthetic cases

    @Test
    fun toggleProperties_explicitOffOverridesStyle() {
        val styles = """
            <w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:rPr><w:b/><w:i/><w:caps/><w:strike/></w:rPr></w:style>
        """
        val doc = parse(
            """<w:p><w:r><w:t>on</w:t></w:r><w:r><w:rPr><w:b w:val="0"/><w:i w:val="false"/><w:caps w:val="off"/><w:strike w:val="0"/></w:rPr><w:t>off</w:t></w:r></w:p>""",
            styles,
        )
        val p = paragraphs(doc.blocks).single()
        val on = p.run("on")
        assertTrue(on.bold && on.italic && on.caps && on.strike)
        val off = p.run("off")
        assertFalse(off.bold || off.italic || off.caps || off.strike)
    }

    @Test
    fun basedOnChain_mergesAndSurvivesCycles() {
        val styles = """
            <w:docDefaults><w:rPrDefault><w:rPr><w:sz w:val="20"/></w:rPr></w:rPrDefault></w:docDefaults>
            <w:style w:type="paragraph" w:styleId="A"><w:pPr><w:jc w:val="center"/></w:pPr><w:rPr><w:sz w:val="40"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="B"><w:basedOn w:val="A"/><w:rPr><w:color w:val="FF0000"/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="C"><w:basedOn w:val="B"/><w:rPr><w:i/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="X"><w:basedOn w:val="Y"/><w:rPr><w:b/></w:rPr></w:style>
            <w:style w:type="paragraph" w:styleId="Y"><w:basedOn w:val="X"/><w:rPr><w:u w:val="single"/></w:rPr></w:style>
            <w:style w:type="character" w:styleId="Emph"><w:rPr><w:b/><w:color w:val="00FF00"/></w:rPr></w:style>
        """
        val doc = parse(
            """
            <w:p><w:pPr><w:pStyle w:val="C"/></w:pPr><w:r><w:t>chain</w:t></w:r><w:r><w:rPr><w:rStyle w:val="Emph"/></w:rPr><w:t>emph</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="X"/></w:pPr><w:r><w:t>cycle</w:t></w:r></w:p>
            <w:p><w:pPr><w:pStyle w:val="Missing"/></w:pPr><w:r><w:t>missing</w:t></w:r></w:p>
            """,
            styles,
        )
        val paras = paragraphs(doc.blocks)
        val chain = paras[0].run("chain")
        assertEquals(20f, chain.sizePt, 0.001f)
        assertEquals(color(0xFF0000), chain.color)
        assertTrue(chain.italic)
        assertEquals(DocxAlignment.CENTER, paras[0].props.alignment)
        val emph = paras[0].run("emph")
        assertTrue(emph.bold && emph.italic)
        assertEquals(color(0x00FF00), emph.color)

        val cycle = paras[1].run("cycle")
        assertTrue(cycle.bold && cycle.underline)

        assertEquals(10f, paras[2].run("missing").sizePt, 0.001f)
    }

    @Test
    fun numbering_overridesRestartsAndMultiLevelText() {
        val numbering = """
            <w:abstractNum w:abstractNumId="0">
              <w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="decimal"/><w:lvlText w:val="%1."/></w:lvl>
              <w:lvl w:ilvl="1"><w:start w:val="1"/><w:numFmt w:val="lowerLetter"/><w:lvlText w:val="%2)"/></w:lvl>
              <w:lvl w:ilvl="2"><w:start w:val="1"/><w:numFmt w:val="lowerRoman"/><w:lvlText w:val="%1.%2.%3"/></w:lvl>
            </w:abstractNum>
            <w:abstractNum w:abstractNumId="1">
              <w:lvl w:ilvl="0"><w:start w:val="3"/><w:numFmt w:val="upperRoman"/><w:lvlText w:val="%1:"/></w:lvl>
            </w:abstractNum>
            <w:num w:numId="1"><w:abstractNumId w:val="0"/></w:num>
            <w:num w:numId="2"><w:abstractNumId w:val="0"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="5"/></w:lvlOverride></w:num>
            <w:num w:numId="3"><w:abstractNumId w:val="1"/></w:num>
            <w:num w:numId="4"><w:abstractNumId w:val="0"/></w:num>
        """
        fun item(numId: Int, ilvl: Int) =
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="$ilvl"/><w:numId w:val="$numId"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>"""
        val body = listOf(
            item(1, 0), item(1, 1), item(1, 1), item(1, 2), item(1, 0), item(1, 1),
            item(2, 0), item(2, 0),
            item(4, 0),
            item(3, 0), item(3, 0),
            """<w:p><w:pPr><w:numPr><w:ilvl w:val="0"/><w:numId w:val="0"/></w:numPr></w:pPr><w:r><w:t>x</w:t></w:r></w:p>""",
            item(9, 0),
        ).joinToString("")
        val labels = paragraphs(parse(body, numbering = numbering).blocks).map { it.label?.text }
        assertEquals(
            listOf(
                "1.", "a)", "b)", "1.b.i", "2.", "a)",
                // numId 2 restarts the shared abstract list at 5; numId 4 (same abstract) continues it.
                "5.", "6.", "7.",
                "III:", "IV:",
                null, null,
            ),
            labels,
        )
    }

    @Test
    fun alternateContent_prefersChoiceAndFallsBackWhenChoiceIsUnusable() {
        val mc = """
            <w:p><mc:AlternateContent><mc:Choice Requires="wps"><w:r><w:t>choice</w:t></w:r></mc:Choice>
              <mc:Fallback><w:r><w:t>fallback</w:t></w:r></mc:Fallback></mc:AlternateContent></w:p>
            <w:p><w:r><mc:AlternateContent><mc:Choice Requires="wps"><w:drawing><wp:anchor><wp:extent cx="12700" cy="12700"/></wp:anchor></w:drawing></mc:Choice>
              <mc:Fallback><w:t>fallback</w:t></mc:Fallback></mc:AlternateContent></w:r></w:p>
            <mc:AlternateContent><mc:Choice Requires="w14"><w:p><w:r><w:t>block choice</w:t></w:r></w:p></mc:Choice>
              <mc:Fallback><w:p><w:r><w:t>block fallback</w:t></w:r></w:p></mc:Fallback></mc:AlternateContent>
        """
        val texts = paragraphs(parse(mc).blocks).map { it.text() }
        assertEquals(listOf("choice", "fallback", "block choice"), texts)
    }

    @Test
    fun hiddenTextSkipped_unstyledHyperlinkLooksLikeALink_symbolsMapped() {
        val doc = parse(
            """<w:p><w:r><w:t>shown</w:t></w:r><w:r><w:rPr><w:vanish/></w:rPr><w:t>hidden</w:t></w:r>
               <w:hyperlink r:id="rId9"><w:r><w:t>link</w:t></w:r></w:hyperlink>
               <w:r><w:sym w:font="Symbol" w:char="F0B7"/></w:r><w:r><w:br/><w:t>after</w:t></w:r></w:p>""",
        )
        val p = paragraphs(doc.blocks).single()
        assertEquals("shownlink•after", p.text())
        assertTrue(p.items.contains(DocxInline.LineBreak))
        val link = p.run("link")
        assertEquals(color(0x0563C1), link.color)
        assertTrue(link.underline)
    }

    @Test
    fun sectionProperties_defaultsAndSanitising() {
        val doc = parse(
            """<w:p><w:pPr><w:sectPr><w:pgSz w:w="15840" w:h="12240"/></w:sectPr></w:pPr><w:r><w:t>landscape</w:t></w:r></w:p>
               <w:sectPr><w:pgSz w:w="12240" w:h="15840"/><w:pgMar w:top="-1440" w:right="99999" w:bottom="720" w:left="99999"/></w:sectPr>""",
        )
        assertEquals(612f, doc.page.widthPt, 0.001f)
        assertEquals(792f, doc.page.heightPt, 0.001f)
        assertEquals(72f, doc.page.marginTopPt, 0.001f)
        assertEquals(36f, doc.page.marginBottomPt, 0.001f)
        assertTrue(doc.page.contentWidthPt >= 72f)
        assertTrue(paragraphs(doc.blocks).single().sectionBreakAfter)
    }

    @Test
    fun tables_vMergeBordersAndNesting() {
        val doc = parse(
            """<w:tbl><w:tblPr><w:tblBorders><w:top w:val="single" w:sz="16" w:color="FF0000"/><w:insideH w:val="nil"/></w:tblBorders>
                 <w:tblCellMar><w:left w:w="108" w:type="dxa"/></w:tblCellMar></w:tblPr>
               <w:tblGrid><w:gridCol w:w="2000"/><w:gridCol w:w="2000"/></w:tblGrid>
               <w:tr><w:trPr><w:tblHeader/><w:trHeight w:val="400" w:hRule="exact"/></w:trPr>
                 <w:tc><w:tcPr><w:vMerge w:val="restart"/></w:tcPr><w:p><w:r><w:t>a</w:t></w:r></w:p></w:tc>
                 <w:tc><w:tbl><w:tr><w:tc><w:p><w:r><w:t>nested</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p/></w:tc></w:tr>
               <w:tr><w:tc><w:tcPr><w:vMerge/><w:tcBorders><w:bottom w:val="double" w:sz="4"/></w:tcBorders></w:tcPr><w:p/></w:tc>
                 <w:tc><w:p><w:r><w:t>b</w:t></w:r></w:p></w:tc></w:tr></w:tbl>""",
        )
        val table = doc.blocks.single() as DocxTable
        assertEquals(2f, table.borders.top!!.widthPt, 0.001f)
        assertEquals(color(0xFF0000), table.borders.top!!.color)
        assertFalse(table.borders.insideH!!.isVisible)
        assertEquals(5.4f, table.cellMargins.leftPt, 0.001f)
        assertEquals(DocxCellMargins.DEFAULT_CELL_PADDING_V_PT, table.cellMargins.topPt, 0.001f)
        assertTrue(table.rows[0].isHeader)
        assertTrue(table.rows[0].exactHeight)
        assertEquals(20f, table.rows[0].heightPt, 0.001f)
        assertEquals(DocxVMerge.RESTART, table.rows[0].cells[0].vMerge)
        assertEquals(DocxVMerge.CONTINUE, table.rows[1].cells[0].vMerge)
        assertTrue(table.rows[1].cells[0].borders.bottom!!.isVisible)
        assertTrue(table.rows[0].cells[1].blocks.first() is DocxTable)
        assertEquals(listOf("a", "nested", "", "", "b"), paragraphs(doc.blocks).map { it.text() })
    }

    // ------------------------------------------------------------------ pure helpers

    @Test
    fun numberFormats() {
        assertEquals("MCMXCIV", NumberFormats.roman(1994))
        assertEquals("iv", NumberFormats.format(4, "lowerRoman"))
        assertEquals("aa", NumberFormats.format(27, "lowerLetter"))
        assertEquals("C", NumberFormats.format(3, "upperLetter"))
        assertEquals("07", NumberFormats.format(7, "decimalZero"))
        assertEquals("12", NumberFormats.format(12, "someUnknownFormat"))
        assertEquals("• ✓", NumberFormats.mapSymbolChars(" "))
        assertEquals("o", NumberFormats.mapSymbolChars("o"))
    }

    @Test
    fun values() {
        assertEquals(36f, OoxmlValues.twipsToPt("720")!!, 0.001f)
        assertEquals(72f, OoxmlValues.twipsToPt("1in")!!, 0.001f)
        assertEquals(12f, OoxmlValues.twipsToPt("12pt")!!, 0.001f)
        assertNull(OoxmlValues.twipsToPt("abc"))
        assertEquals(10.5f, OoxmlValues.halfPointsToPt("21")!!, 0.001f)
        assertEquals(240f, OoxmlValues.emuToPt("3048000")!!, 0.001f)
        assertEquals(color(0x2F5496), OoxmlValues.color("2F5496"))
        assertEquals(OoxmlValues.AUTO_COLOR, OoxmlValues.color("auto"))
        assertNull(OoxmlValues.color("zzz"))
        assertTrue(OoxmlValues.onOff(null))
        assertFalse(OoxmlValues.onOff("off"))
        assertEquals(75f, OoxmlValues.cssLengthToPt("100px")!!, 0.001f)
        assertEquals(144f, OoxmlValues.cssLengthToPt("2in")!!, 0.001f)
    }

    @Test
    fun partNameResolution() {
        assertEquals("word/media/image1.png", DocxParser.resolvePartName("word/", "media/image1.png"))
        assertEquals("media/a b.png", DocxParser.resolvePartName("word/", "../media/a%20b.png"))
        assertEquals("word/media/x.png", DocxParser.resolvePartName("word/", "/word/media/x.png"))
        assertEquals("word/x.png", DocxParser.resolvePartName("word/", "./x.png"))
    }

    @Test
    fun parsedDocumentIsUsableWithoutOptionalParts() {
        val doc = parse("""<w:p/>""")
        val p = paragraphs(doc.blocks).single()
        assertTrue(p.items.isEmpty())
        assertNotNull(p.markRun)
    }

    private companion object {
        const val NS = """xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" """ +
            """xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" """ +
            """xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing" """ +
            """xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006""""
    }
}
