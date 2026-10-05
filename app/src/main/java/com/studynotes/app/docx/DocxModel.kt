package com.studynotes.app.docx

/*
 * Resolved, renderer-ready model of a Word document. Pure Kotlin (no android.*) so the parser that
 * produces it is testable on the JVM. All lengths are in PDF points (1/72 inch); colours are opaque
 * ARGB ints. Styles, numbering and relationships are already resolved: every paragraph and run
 * carries its final formatting.
 */

/** A parsed .docx: page setup, body content and the foot/endnotes that the body references. */
data class DocxDocument(
    /** Page setup of the body's final section; also used for the notes. */
    val page: DocxPageSetup,
    val blocks: List<DocxBlock>,
    /** Notes in reference order; rendered at the end under a "Notes" heading. */
    val notes: List<DocxNote> = emptyList(),
    /**
     * Sections in document order, each starting at a top-level index of [blocks]. Empty means one
     * section using [page]. When present, the first starts at block 0 and the last uses [page].
     */
    val sections: List<DocxSection> = emptyList(),
)

/** A document section: the page setup used from top-level block [firstBlock] on. */
data class DocxSection(val firstBlock: Int, val page: DocxPageSetup)

/** Page size and margins of a section. */
data class DocxPageSetup(
    val widthPt: Float,
    val heightPt: Float,
    val marginTopPt: Float,
    val marginRightPt: Float,
    val marginBottomPt: Float,
    val marginLeftPt: Float,
) {
    val contentWidthPt: Float get() = widthPt - marginLeftPt - marginRightPt
    val contentHeightPt: Float get() = heightPt - marginTopPt - marginBottomPt

    companion object {
        /** A4 portrait with 1-inch margins: Word's default when a document has no `w:sectPr`. */
        val DEFAULT = DocxPageSetup(595f, 842f, 72f, 72f, 72f, 72f)
    }
}

/** Body-level content: a paragraph or a table. */
sealed interface DocxBlock

data class DocxParagraph(
    val props: DocxParagraphProps,
    /** Formatting of the paragraph mark; sizes empty paragraphs. */
    val markRun: DocxRunProps,
    /** Bullet or number, already formatted ("1.", "•", "a)"), or null. */
    val label: DocxListLabel?,
    val items: List<DocxInline>,
    /** The paragraph ends a section whose successor starts on a new page. */
    val sectionBreakAfter: Boolean = false,
) : DocxBlock

data class DocxListLabel(
    val text: String,
    val run: DocxRunProps,
    val suffix: DocxLabelSuffix,
)

/** What separates a list label from the paragraph text (`w:suff`). */
enum class DocxLabelSuffix { TAB, SPACE, NOTHING }

enum class DocxAlignment { LEFT, CENTER, RIGHT, JUSTIFY }

/** `w:spacing/@w:lineRule`. For [AUTO] the line value is a multiplier, otherwise points. */
enum class DocxLineRule { AUTO, EXACT, AT_LEAST }

data class DocxParagraphProps(
    val styleId: String? = null,
    val alignment: DocxAlignment = DocxAlignment.LEFT,
    val spaceBeforePt: Float = 0f,
    val spaceAfterPt: Float = 0f,
    val lineRule: DocxLineRule = DocxLineRule.AUTO,
    /** Multiplier for [DocxLineRule.AUTO] (1 = single), points otherwise. */
    val lineValue: Float = 1f,
    val indentLeftPt: Float = 0f,
    val indentRightPt: Float = 0f,
    /** First-line offset relative to [indentLeftPt]; negative for a hanging indent. */
    val firstLinePt: Float = 0f,
    val keepNext: Boolean = false,
    val pageBreakBefore: Boolean = false,
    /** Ignore spacing between paragraphs of the same style (tight lists). */
    val contextualSpacing: Boolean = false,
    /** Custom tab stops, measured from the start of the text column, ascending by position. */
    val tabStops: List<DocxTabStop> = emptyList(),
    /** Paragraph background (`w:shd/@w:fill`). */
    val shading: Int? = null,
)

/** How text after a tab lines up with the stop (`w:tab/@w:val`). */
enum class DocxTabAlign { LEFT, CENTER, RIGHT, DECIMAL }

data class DocxTabStop(val posPt: Float, val align: DocxTabAlign = DocxTabAlign.LEFT)

enum class DocxVertAlign { BASELINE, SUPERSCRIPT, SUBSCRIPT }

data class DocxRunProps(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val sizePt: Float = DEFAULT_FONT_SIZE_PT,
    /** Text colour, null = automatic (near-black). */
    val color: Int? = null,
    /** Highlight or run shading behind the text. */
    val highlight: Int? = null,
    val vertAlign: DocxVertAlign = DocxVertAlign.BASELINE,
    val caps: Boolean = false,
    val smallCaps: Boolean = false,
    /** Font family name as written in the document (e.g. "Calibri"), or null for the default. */
    val fontName: String? = null,
) {
    companion object {
        /** Word's modern "Normal" size, used when a document defines no default. */
        const val DEFAULT_FONT_SIZE_PT = 11f
    }
}

/** Paragraph content. */
sealed interface DocxInline {
    data class Text(val text: String, val run: DocxRunProps) : DocxInline
    data class Tab(val run: DocxRunProps) : DocxInline
    data object LineBreak : DocxInline
    data object PageBreak : DocxInline

    /**
     * A picture. [entryName] is the resolved zip entry (e.g. "word/media/image1.png"). Sizes are
     * the document's display size; 0 means unknown (the renderer then uses the intrinsic size).
     *
     * Crop values are fractions of the source picture cut off each edge (Word keeps the original
     * picture and records the crop). Negative values add empty space on that edge instead. Always
     * sanitised so the remaining part is non-empty: `cropLeft + cropRight < 1`, same vertically.
     */
    data class Image(
        val entryName: String,
        val widthPt: Float,
        val heightPt: Float,
        val cropLeft: Float = 0f,
        val cropTop: Float = 0f,
        val cropRight: Float = 0f,
        val cropBottom: Float = 0f,
    ) : DocxInline {
        val isCropped: Boolean get() = cropLeft != 0f || cropTop != 0f || cropRight != 0f || cropBottom != 0f
    }

    /** A foot/endnote reference mark ("1", "ii"). */
    data class NoteRef(val label: String, val run: DocxRunProps) : DocxInline
}

data class DocxTable(
    /** Grid column widths from `w:tblGrid` (may be empty when the document omits it). */
    val gridPt: List<Float>,
    val rows: List<DocxTableRow>,
    val indentPt: Float = 0f,
    val alignment: DocxAlignment = DocxAlignment.LEFT,
    val borders: DocxTableBorders = DocxTableBorders(),
    val cellMargins: DocxCellMargins = DocxCellMargins(),
) : DocxBlock

data class DocxTableRow(
    val cells: List<DocxTableCell>,
    /** Grid columns skipped before the first cell (`w:gridBefore`). */
    val gridBefore: Int = 0,
    val heightPt: Float = 0f,
    /** True for `w:hRule="exact"`; otherwise [heightPt] is a minimum. */
    val exactHeight: Boolean = false,
    /** Repeated at the top of each page the table continues on. */
    val isHeader: Boolean = false,
)

enum class DocxVMerge { NONE, RESTART, CONTINUE }

enum class DocxCellVAlign { TOP, CENTER, BOTTOM }

data class DocxTableCell(
    val blocks: List<DocxBlock>,
    val gridSpan: Int = 1,
    val vMerge: DocxVMerge = DocxVMerge.NONE,
    val shading: Int? = null,
    val vAlign: DocxCellVAlign = DocxCellVAlign.TOP,
    /** Cell-level border overrides; null edges fall back to the table borders. */
    val borders: DocxCellBorders = DocxCellBorders(),
)

/** One border line. [widthPt] 0 means "explicitly no border". */
data class DocxBorder(val widthPt: Float, val color: Int) {
    val isVisible: Boolean get() = widthPt > 0f

    companion object {
        val NONE = DocxBorder(0f, 0)
    }
}

data class DocxTableBorders(
    val top: DocxBorder? = null,
    val left: DocxBorder? = null,
    val bottom: DocxBorder? = null,
    val right: DocxBorder? = null,
    val insideH: DocxBorder? = null,
    val insideV: DocxBorder? = null,
)

data class DocxCellBorders(
    val top: DocxBorder? = null,
    val left: DocxBorder? = null,
    val bottom: DocxBorder? = null,
    val right: DocxBorder? = null,
)

/** Padding inside table cells. The defaults are Word's ("Normal Table": 0 / 0.08 in / 0 / 0.08 in). */
data class DocxCellMargins(
    val topPt: Float = DEFAULT_CELL_PADDING_V_PT,
    val leftPt: Float = DEFAULT_CELL_PADDING_H_PT,
    val bottomPt: Float = DEFAULT_CELL_PADDING_V_PT,
    val rightPt: Float = DEFAULT_CELL_PADDING_H_PT,
) {
    companion object {
        const val DEFAULT_CELL_PADDING_V_PT = 0f
        const val DEFAULT_CELL_PADDING_H_PT = 5.4f
    }
}

/** A foot- or endnote: its reference label and content. */
data class DocxNote(val label: String, val blocks: List<DocxBlock>)

/** Generic family a document font maps to; the device has no Calibri or Times New Roman. */
enum class DocxFontKind {
    SANS, SERIF, MONO;

    companion object {
        private val MONO_HINTS = listOf(
            "mono", "courier", "consolas", "menlo", "monaco", "lucida console", "source code",
            "fira code", "inconsolata", "andale", "typewriter",
        )
        /** Sans families whose names would otherwise match a serif hint ("Century Gothic"). */
        private val SANS_HINTS = listOf("sans", "gothic", "grotesk", "grotesque")
        private val SERIF_HINTS = listOf(
            "times", "georgia", "cambria", "garamond", "book antiqua", "palatino", "baskerville",
            "bodoni", "century", "constantia", "didot", "minion", "perpetua", "rockwell", "sylfaen",
            "bookman", "caslon", "charter", "roman", "serif", "mincho", "songti", "batang",
        )

        /** Heuristic by name; unknown and missing names map to sans (Word's modern default look). */
        fun of(fontName: String?): DocxFontKind {
            val name = fontName?.lowercase() ?: return SANS
            return when {
                MONO_HINTS.any { it in name } -> MONO
                SANS_HINTS.any { it in name } -> SANS
                SERIF_HINTS.any { it in name } -> SERIF
                else -> SANS
            }
        }
    }
}
