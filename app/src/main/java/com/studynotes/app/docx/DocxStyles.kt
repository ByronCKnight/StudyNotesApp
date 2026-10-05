package com.studynotes.app.docx

/*
 * Partial formatting ("patches") as written at one level of the Word style hierarchy, and the style
 * sheet that chains them. A null field means "not specified here, inherit". Resolution overlays
 * patches from the most general level (document defaults) to the most specific (direct formatting).
 */

internal data class RunPatch(
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underline: Boolean? = null,
    val strike: Boolean? = null,
    val sizePt: Float? = null,
    /** [OoxmlValues.AUTO_COLOR] = explicitly automatic. */
    val color: Int? = null,
    /** [OoxmlValues.AUTO_COLOR] = explicitly none. */
    val highlight: Int? = null,
    val vertAlign: DocxVertAlign? = null,
    val caps: Boolean? = null,
    val smallCaps: Boolean? = null,
    val fontName: String? = null,
    val hidden: Boolean? = null,
) {
    /** This patch with every field that [top] specifies replaced by [top]'s value. */
    fun overlay(top: RunPatch?): RunPatch {
        if (top == null || top == EMPTY) return this
        return RunPatch(
            bold = top.bold ?: bold,
            italic = top.italic ?: italic,
            underline = top.underline ?: underline,
            strike = top.strike ?: strike,
            sizePt = top.sizePt ?: sizePt,
            color = top.color ?: color,
            highlight = top.highlight ?: highlight,
            vertAlign = top.vertAlign ?: vertAlign,
            caps = top.caps ?: caps,
            smallCaps = top.smallCaps ?: smallCaps,
            fontName = top.fontName ?: fontName,
            hidden = top.hidden ?: hidden,
        )
    }

    fun toProps(): DocxRunProps = DocxRunProps(
        bold = bold == true,
        italic = italic == true,
        underline = underline == true,
        strike = strike == true,
        sizePt = sizePt?.takeIf { it in MIN_FONT_PT..MAX_FONT_PT } ?: DocxRunProps.DEFAULT_FONT_SIZE_PT,
        color = color?.takeIf { it != OoxmlValues.AUTO_COLOR },
        highlight = highlight?.takeIf { it != OoxmlValues.AUTO_COLOR },
        vertAlign = vertAlign ?: DocxVertAlign.BASELINE,
        caps = caps == true,
        smallCaps = smallCaps == true,
        fontName = fontName,
    )

    companion object {
        val EMPTY = RunPatch()
        private const val MIN_FONT_PT = 1f
        private const val MAX_FONT_PT = 400f
    }
}

/** A tab stop instruction: add a stop at [posPt] aligned as [align], or remove an inherited one ([clear]). */
internal data class TabPatch(val posPt: Float, val clear: Boolean, val align: DocxTabAlign = DocxTabAlign.LEFT)

internal data class ParaPatch(
    val alignment: DocxAlignment? = null,
    val spaceBeforePt: Float? = null,
    val spaceAfterPt: Float? = null,
    val lineRule: DocxLineRule? = null,
    /** Raw `w:line` value (240ths of a line for auto, twips otherwise). */
    val lineRaw: Float? = null,
    val indentLeftPt: Float? = null,
    val indentRightPt: Float? = null,
    val firstLinePt: Float? = null,
    val keepNext: Boolean? = null,
    val pageBreakBefore: Boolean? = null,
    val contextualSpacing: Boolean? = null,
    /** Tab instructions in document order; they accumulate across levels. */
    val tabs: List<TabPatch> = emptyList(),
    val shading: Int? = null,
    val numId: String? = null,
    val ilvl: Int? = null,
) {
    fun overlay(top: ParaPatch?): ParaPatch {
        if (top == null || top == EMPTY) return this
        return ParaPatch(
            alignment = top.alignment ?: alignment,
            spaceBeforePt = top.spaceBeforePt ?: spaceBeforePt,
            spaceAfterPt = top.spaceAfterPt ?: spaceAfterPt,
            lineRule = top.lineRule ?: lineRule,
            lineRaw = top.lineRaw ?: lineRaw,
            indentLeftPt = top.indentLeftPt ?: indentLeftPt,
            indentRightPt = top.indentRightPt ?: indentRightPt,
            firstLinePt = top.firstLinePt ?: firstLinePt,
            keepNext = top.keepNext ?: keepNext,
            pageBreakBefore = top.pageBreakBefore ?: pageBreakBefore,
            contextualSpacing = top.contextualSpacing ?: contextualSpacing,
            tabs = if (top.tabs.isEmpty()) tabs else tabs + top.tabs,
            shading = top.shading ?: shading,
            numId = top.numId ?: numId,
            ilvl = top.ilvl ?: ilvl,
        )
    }

    fun toProps(styleId: String?): DocxParagraphProps {
        val rule = lineRule ?: DocxLineRule.AUTO
        // NaN/Infinity never reach layout: a non-finite value counts as unspecified.
        val raw = lineRaw?.takeIf { it.isFinite() }
        val lineValue = when {
            raw == null || raw <= 0f -> if (rule == DocxLineRule.AUTO) 1f else 0f
            rule == DocxLineRule.AUTO -> (raw / 240f).coerceIn(MIN_LINE_MULTIPLIER, MAX_LINE_MULTIPLIER)
            else -> (raw / 20f).coerceIn(MIN_LINE_PT, MAX_LINE_PT)
        }
        // Later instructions win: a stop redefined at the same position takes the new alignment.
        val stops = java.util.TreeMap<Float, DocxTabAlign>()
        for (t in tabs) {
            if (!t.posPt.isFinite()) continue
            stops.keys.removeAll { kotlin.math.abs(it - t.posPt) < 0.5f }
            if (!t.clear && t.posPt > 0f) stops[t.posPt] = t.align
        }
        return DocxParagraphProps(
            styleId = styleId,
            alignment = alignment ?: DocxAlignment.LEFT,
            spaceBeforePt = (spaceBeforePt ?: 0f).coerceIn(0f, MAX_SPACING_PT),
            spaceAfterPt = (spaceAfterPt ?: 0f).coerceIn(0f, MAX_SPACING_PT),
            // A zero exact/at-least line height is meaningless; fall back to single spacing.
            lineRule = if (rule != DocxLineRule.AUTO && lineValue <= 0f) DocxLineRule.AUTO else rule,
            lineValue = if (rule != DocxLineRule.AUTO && lineValue <= 0f) 1f else lineValue,
            indentLeftPt = indentLeftPt ?: 0f,
            indentRightPt = indentRightPt ?: 0f,
            firstLinePt = firstLinePt ?: 0f,
            keepNext = keepNext == true,
            pageBreakBefore = pageBreakBefore == true,
            contextualSpacing = contextualSpacing == true,
            tabStops = stops.map { (pos, align) -> DocxTabStop(pos, align) },
            shading = shading?.takeIf { it != OoxmlValues.AUTO_COLOR },
        )
    }

    companion object {
        val EMPTY = ParaPatch()
        private const val MAX_SPACING_PT = 400f
        private const val MIN_LINE_MULTIPLIER = 0.5f
        private const val MAX_LINE_MULTIPLIER = 5f
        private const val MIN_LINE_PT = 1f

        /** 22 inches: Word's largest page height; an exact line can't usefully be taller. */
        private const val MAX_LINE_PT = 1584f
    }
}

/** Cell margins as specified at one level (`w:tblCellMar`); null = inherit. */
internal data class CellMarginPatch(
    val topPt: Float? = null,
    val leftPt: Float? = null,
    val bottomPt: Float? = null,
    val rightPt: Float? = null,
) {
    fun overlay(top: CellMarginPatch): CellMarginPatch = CellMarginPatch(
        topPt = top.topPt ?: topPt,
        leftPt = top.leftPt ?: leftPt,
        bottomPt = top.bottomPt ?: bottomPt,
        rightPt = top.rightPt ?: rightPt,
    )

    fun toMargins(): DocxCellMargins {
        val v = DocxCellMargins.DEFAULT_CELL_PADDING_V_PT
        val h = DocxCellMargins.DEFAULT_CELL_PADDING_H_PT
        return DocxCellMargins(
            topPt = (topPt ?: v).coerceIn(0f, MAX_PADDING_PT),
            leftPt = (leftPt ?: h).coerceIn(0f, MAX_PADDING_PT),
            bottomPt = (bottomPt ?: v).coerceIn(0f, MAX_PADDING_PT),
            rightPt = (rightPt ?: h).coerceIn(0f, MAX_PADDING_PT),
        )
    }

    private companion object {
        const val MAX_PADDING_PT = 72f
    }
}

/** Edge-wise overlay: edges [top] specifies win (including explicit "none"). */
internal fun DocxTableBorders.overlay(top: DocxTableBorders): DocxTableBorders = DocxTableBorders(
    top = top.top ?: this.top,
    left = top.left ?: left,
    bottom = top.bottom ?: bottom,
    right = top.right ?: right,
    insideH = top.insideH ?: insideH,
    insideV = top.insideV ?: insideV,
)

internal enum class StyleType { PARAGRAPH, CHARACTER, TABLE, NUMBERING, OTHER }

internal data class StyleDef(
    val id: String,
    val type: StyleType,
    val basedOn: String?,
    val isDefault: Boolean,
    val pPr: ParaPatch = ParaPatch.EMPTY,
    val rPr: RunPatch = RunPatch.EMPTY,
    val tableBorders: DocxTableBorders = DocxTableBorders(),
    val cellMargins: CellMarginPatch = CellMarginPatch(),
)

/** The parsed styles part: document defaults plus named styles. */
internal class DocxStyles(
    private val styles: Map<String, StyleDef>,
    val defaultRun: RunPatch,
    val defaultPara: ParaPatch,
) {
    val defaultParagraphStyleId: String? =
        styles.values.firstOrNull { it.type == StyleType.PARAGRAPH && it.isDefault }?.id

    val defaultTableStyleId: String? =
        styles.values.firstOrNull { it.type == StyleType.TABLE && it.isDefault }?.id

    /**
     * The `basedOn` chain of [id], most general first. Cycle-safe (a style seen twice ends the
     * chain) and depth-limited; unknown ids end the chain.
     */
    fun chain(id: String?): List<StyleDef> {
        if (id == null) return emptyList()
        val out = ArrayDeque<StyleDef>()
        val seen = HashSet<String>()
        var current: String? = id
        while (current != null && seen.add(current) && out.size < MAX_CHAIN) {
            val style = styles[current] ?: break
            out.addFirst(style)
            current = style.basedOn
        }
        return out
    }

    fun contains(id: String): Boolean = id in styles

    companion object {
        val EMPTY = DocxStyles(emptyMap(), RunPatch.EMPTY, ParaPatch.EMPTY)
        private const val MAX_CHAIN = 32
    }
}
