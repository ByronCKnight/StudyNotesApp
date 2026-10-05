package com.studynotes.app.docx

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.max

/** Read access to the parts (zip entries) of a .docx package. */
internal fun interface DocxParts {
    /** Opens part [name] (entry path without a leading slash), or returns null if it doesn't exist. */
    fun open(name: String): InputStream?
}

/** [DocxParts] over a zip. Falls back to a case-insensitive lookup, which some producers need. */
internal fun ZipFile.asDocxParts(): DocxParts {
    val byLowerName = HashMap<String, ZipEntry>()
    for (entry in entries()) {
        if (!entry.isDirectory) byLowerName.putIfAbsent(entry.name.lowercase(), entry)
    }
    return DocxParts { name ->
        val entry = getEntry(name) ?: byLowerName[name.lowercase()]
        entry?.let { getInputStream(it) }
    }
}

/**
 * Fails with [DocxConversionException] ([DocxConverter.TOO_LARGE_MESSAGE]) once more than [limit]
 * bytes have been read, so a zip bomb can't make the XML parser run (nearly) forever.
 */
internal class SizeLimitedInputStream(input: InputStream, private val limit: Long) : java.io.FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) count(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) count(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) count(skipped)
        return skipped
    }

    private fun count(n: Long) {
        total += n
        if (total > limit) throw DocxConversionException(DocxConverter.TOO_LARGE_MESSAGE)
    }
}

/**
 * Parses a .docx package into a resolved [DocxDocument]. Pure JVM code (ZipFile + XmlPullParser), so
 * it runs in unit tests.
 *
 * Formatting is resolved the way Word does it: document defaults, then the paragraph style's
 * `basedOn` chain, then (for list paragraphs) the numbering level, then direct paragraph formatting;
 * runs add the character style chain and direct run formatting on top. Anything unknown or malformed
 * is skipped or replaced by a default; only a package without a readable main document fails.
 */
internal class DocxParser private constructor(private val parts: DocxParts, private val checkpoint: () -> Unit) {

    private data class Relationship(val type: String, val target: String, val external: Boolean)

    private enum class NoteKind { FOOTNOTE, ENDNOTE }

    private data class NoteKey(val kind: NoteKind, val id: String)

    /**
     * A `w:sectPr`. [startType] is its `w:type`: how *this* section starts relative to the previous
     * one (null = nextPage).
     */
    private data class SectionInfo(val page: DocxPageSetup, val startType: String?) {
        val startsOnNewPage: Boolean get() = startType != "continuous" && startType != "nextColumn"
    }

    /** A body paragraph that ends a section: its index in the body's block list and its sectPr. */
    private class SectionEnd(val blockIndex: Int, val info: SectionInfo)

    private class PPrResult(val styleId: String?, val patch: ParaPatch, val mark: RunPatch, val section: SectionInfo?)

    private class RPrResult(val styleId: String?, val patch: RunPatch)

    /** Paragraph-level inherited formatting, computed once per paragraph. */
    private class ParaContext(val styleId: String?, val stylePara: ParaPatch, val runBase: RunPatch)

    private class RunState(var props: DocxRunProps, var hidden: Boolean)

    /**
     * Collects a paragraph's inline items, merging adjacent text with identical formatting. The
     * text being merged is kept in a StringBuilder, so thousands of identically formatted runs stay
     * linear.
     */
    private class InlineBuilder {
        private val items = ArrayList<DocxInline>()
        private val pendingText = StringBuilder()
        private var pendingRun: DocxRunProps? = null

        /** Incremented on every change; lets mc:AlternateContent tell whether a branch produced output. */
        var changes = 0
            private set

        fun text(s: String, run: DocxRunProps) {
            if (s.isEmpty()) return
            if (pendingRun != run) {
                flush()
                pendingRun = run
            }
            pendingText.append(s)
            changes++
        }

        fun add(item: DocxInline) {
            flush()
            items.add(item)
            changes++
        }

        fun build(): List<DocxInline> {
            flush()
            return items
        }

        private fun flush() {
            val run = pendingRun ?: return
            if (pendingText.isNotEmpty()) items.add(DocxInline.Text(pendingText.toString(), run))
            pendingText.setLength(0)
            pendingRun = null
        }
    }

    private val xmlFactory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }
    private var styles = DocxStyles.EMPTY
    private var numbering = DocxNumbering.EMPTY
    private var rels: Map<String, Relationship> = emptyMap()
    private val noteLabels = LinkedHashMap<NoteKey, String>()
    private var footnoteCount = 0
    private var endnoteCount = 0
    private var currentNote: NoteKey? = null

    /** The body's block list; paragraphs added to it directly (or via sdt/customXml wrappers) can end sections. */
    private var bodyBlocks: MutableList<DocxBlock>? = null
    private var bodySection: SectionInfo? = null
    private val sectionEnds = ArrayList<SectionEnd>()

    /** Effective table style of each table being parsed (innermost last); its pPr/rPr apply to cell paragraphs. */
    private val tableStyles = ArrayList<String?>()

    private var ticks = 0

    /** Called for every element of interest; runs [checkpoint] every [CHECKPOINT_INTERVAL] calls. */
    private fun tick() {
        if (++ticks % CHECKPOINT_INTERVAL == 0) checkpoint()
    }

    private fun parseDocument(): DocxDocument {
        val mainPart = findMainDocumentPart(parts) ?: throw invalid()
        rels = readRelationships(mainPart)
        styles = readOptionalPart(partOfType("styles", "word/styles.xml")) { parseStyles(it) } ?: DocxStyles.EMPTY
        numbering = readOptionalPart(partOfType("numbering", "word/numbering.xml")) { parseNumbering(it) }
            ?: DocxNumbering.EMPTY
        checkpoint()

        val blocks = ArrayList<DocxBlock>()
        bodyBlocks = blocks
        try {
            val found = readPart(mainPart) { xp ->
                if (!xp.isW("document")) throw invalid()
                xp.forEachChild { if (xp.isW("body")) parseBlocks(xp, blocks) }
            }
            if (found == null) throw invalid()
        } catch (e: XmlPullParserException) {
            throw invalid(e)
        } catch (e: IOException) {
            throw invalid(e)
        } finally {
            bodyBlocks = null
        }
        checkpoint()
        val sections = resolveSections(blocks)

        val notes = if (noteLabels.isEmpty()) emptyList() else readNotes()
        return DocxDocument(page = sections.last().page, blocks = blocks, notes = notes, sections = sections)
    }

    /**
     * Turns the recorded section ends into [DocxSection]s and marks section breaks. A sectPr's
     * `w:type` says how its own section starts, so the break after section k is decided by section
     * k+1's type: the next paragraph-level sectPr, or the body-level one for the last break.
     */
    private fun resolveSections(blocks: MutableList<DocxBlock>): List<DocxSection> {
        // Without a body-level sectPr Word uses defaults; keep the last explicit setup instead, which
        // is closer to what the author saw.
        val last = bodySection ?: SectionInfo(sectionEnds.lastOrNull()?.info?.page ?: DocxPageSetup.DEFAULT, null)
        val out = ArrayList<DocxSection>(sectionEnds.size + 1)
        var start = 0
        for ((k, end) in sectionEnds.withIndex()) {
            val next = sectionEnds.getOrNull(k + 1)?.info ?: last
            val paragraph = blocks[end.blockIndex] as DocxParagraph
            blocks[end.blockIndex] = paragraph.copy(sectionBreakAfter = next.startsOnNewPage)
            out.add(DocxSection(start, end.info.page))
            start = end.blockIndex + 1
        }
        out.add(DocxSection(start, last.page))
        return out
    }

    // ---------------------------------------------------------------- package plumbing

    /** The main document part: the root officeDocument relationship's target, else word/document.xml. */
    private fun mainDocumentPartOrNull(): String? {
        val rootRels = readOptionalPart("_rels/.rels") { parseRelationships(it, baseDir = "") }.orEmpty()
        val fromRels = rootRels.values.firstOrNull { !it.external && it.type.endsWith("/officeDocument") }?.target
        return listOfNotNull(fromRels, DEFAULT_MAIN_PART).firstOrNull { name -> parts.open(name)?.use { true } == true }
    }

    private fun readRelationships(part: String): Map<String, Relationship> {
        val dir = part.substringBeforeLast('/', "")
        val file = part.substringAfterLast('/')
        val relsPart = if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
        return readOptionalPart(relsPart) { parseRelationships(it, baseDir = if (dir.isEmpty()) "" else "$dir/") }.orEmpty()
    }

    private fun partOfType(typeSuffix: String, fallback: String): String =
        rels.values.firstOrNull { !it.external && it.type.endsWith("/$typeSuffix") }?.target ?: fallback

    /** Runs [block] on the root element of [part]; returns null if the part doesn't exist. */
    private fun <T> readPart(part: String, block: (XmlPullParser) -> T): T? {
        val stream = parts.open(part) ?: return null
        return SizeLimitedInputStream(stream, MAX_PART_BYTES).buffered().use { input ->
            val xp = xmlFactory.newPullParser()
            // Relaxed mode accepts what Word itself tolerates from third-party producers, such as a
            // bare "&" in text. Parsers without the feature simply stay strict.
            try {
                xp.setFeature(FEATURE_RELAXED, true)
            } catch (_: XmlPullParserException) {
            }
            xp.setInput(input, null)
            while (xp.next() != XmlPullParser.START_TAG) {
                if (xp.eventType == XmlPullParser.END_DOCUMENT) throw XmlPullParserException("No root element in $part")
            }
            block(xp)
        }
    }

    /**
     * Like [readPart], but a damaged optional part (styles, numbering, notes) degrades to null.
     * [DocxConversionException] (an oversized part) and cancellation still propagate.
     */
    private fun <T> readOptionalPart(part: String, block: (XmlPullParser) -> T): T? = try {
        readPart(part, block)
    } catch (_: XmlPullParserException) {
        null
    } catch (_: IOException) {
        null
    } catch (e: CancellationException) {
        throw e
    } catch (_: RuntimeException) {
        null
    }

    private fun parseRelationships(xp: XmlPullParser, baseDir: String): Map<String, Relationship> {
        val out = HashMap<String, Relationship>()
        xp.forEachChild {
            if (xp.name == "Relationship") {
                val id = xp.attr("Id")
                val target = xp.attr("Target")
                if (id != null && target != null) {
                    val external = xp.attr("TargetMode").equals("External", ignoreCase = true)
                    val resolved = if (external) target else resolvePartName(baseDir, target)
                    out[id] = Relationship(xp.attr("Type").orEmpty(), resolved, external)
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- styles.xml

    private fun parseStyles(xp: XmlPullParser): DocxStyles {
        var defaultRun = RunPatch.EMPTY
        var defaultPara = ParaPatch.EMPTY
        val map = HashMap<String, StyleDef>()
        xp.forEachChild {
            tick()
            when {
                xp.isW("docDefaults") -> xp.forEachChild {
                    when {
                        xp.isW("rPrDefault") -> xp.forEachChild { if (xp.isW("rPr")) defaultRun = parseRPr(xp).patch }
                        xp.isW("pPrDefault") -> xp.forEachChild { if (xp.isW("pPr")) defaultPara = parsePPr(xp).patch }
                    }
                }
                xp.isW("style") -> parseStyle(xp)?.let { map.putIfAbsent(it.id, it) }
            }
        }
        return DocxStyles(map, defaultRun, defaultPara)
    }

    private fun parseStyle(xp: XmlPullParser): StyleDef? {
        val id = xp.attr("styleId") ?: return null
        val type = when (xp.attr("type")) {
            "paragraph" -> StyleType.PARAGRAPH
            "character" -> StyleType.CHARACTER
            "table" -> StyleType.TABLE
            "numbering" -> StyleType.NUMBERING
            else -> StyleType.OTHER
        }
        val isDefault = xp.attr("default")?.let(OoxmlValues::onOff) == true
        var basedOn: String? = null
        var pPr = ParaPatch.EMPTY
        var rPr = RunPatch.EMPTY
        var borders = DocxTableBorders()
        var margins = CellMarginPatch()
        xp.forEachChild {
            if (xp.isW()) when (xp.name) {
                "basedOn" -> basedOn = xp.attr("val")
                "pPr" -> pPr = parsePPr(xp).patch
                "rPr" -> rPr = parseRPr(xp).patch
                "tblPr" -> xp.forEachChild {
                    when {
                        xp.isW("tblBorders") -> borders = parseBorders(xp)
                        xp.isW("tblCellMar") -> margins = parseCellMargins(xp)
                    }
                }
            }
        }
        return StyleDef(id, type, basedOn, isDefault, pPr, rPr, borders, margins)
    }

    // ---------------------------------------------------------------- numbering.xml

    private fun parseNumbering(xp: XmlPullParser): DocxNumbering {
        val abstracts = HashMap<String, Map<Int, NumLevel>>()
        val styleLinks = HashMap<String, String>()
        val nums = HashMap<String, NumInstance>()
        xp.forEachChild {
            tick()
            when {
                xp.isW("abstractNum") -> {
                    val id = xp.attr("abstractNumId")
                    val levels = HashMap<Int, NumLevel>()
                    var numStyleLink: String? = null
                    xp.forEachChild {
                        when {
                            xp.isW("lvl") -> {
                                val ilvl = OoxmlValues.int(xp.attr("ilvl")) ?: 0
                                val level = parseLevel(xp)
                                if (ilvl in 0 until DocxNumbering.MAX_LEVELS) levels.putIfAbsent(ilvl, level)
                            }
                            xp.isW("numStyleLink") -> numStyleLink = xp.attr("val")?.trim()
                        }
                    }
                    if (id != null && abstracts.putIfAbsent(id, levels) == null) numStyleLink?.let { styleLinks[id] = it }
                }
                xp.isW("num") -> {
                    val numId = xp.attr("numId")
                    var abstractId: String? = null
                    val overrides = HashMap<Int, LevelOverride>()
                    xp.forEachChild {
                        when {
                            xp.isW("abstractNumId") -> abstractId = xp.attr("val")
                            xp.isW("lvlOverride") -> {
                                val ilvl = OoxmlValues.int(xp.attr("ilvl")) ?: 0
                                var start: Int? = null
                                var level: NumLevel? = null
                                xp.forEachChild {
                                    when {
                                        xp.isW("startOverride") -> start = OoxmlValues.int(xp.attr("val"))
                                        xp.isW("lvl") -> level = parseLevel(xp)
                                    }
                                }
                                overrides[ilvl] = LevelOverride(start, level)
                            }
                        }
                    }
                    val abs = abstractId
                    if (numId != null && abs != null) nums.putIfAbsent(numId, NumInstance(abs, overrides))
                }
            }
        }
        resolveNumStyleLinks(abstracts, styleLinks, nums)
        return DocxNumbering(abstracts, nums)
    }

    /**
     * Lists defined through a numbering style: the abstract a `w:num` points at holds only
     * `w:numStyleLink X`; the real levels live in the abstract reached through style X's numId.
     * Repoints such instances at that abstract (which also shares its counters, as Word does).
     */
    private fun resolveNumStyleLinks(
        abstracts: Map<String, Map<Int, NumLevel>>,
        styleLinks: Map<String, String>,
        nums: MutableMap<String, NumInstance>,
    ) {
        if (styleLinks.isEmpty()) return
        val target = HashMap<String, String>()
        for (start in styleLinks.keys) {
            if (abstracts[start]?.isNotEmpty() == true) continue
            var current = start
            val seen = hashSetOf(current)
            var resolved: String? = null
            while (seen.size <= MAX_STYLE_LINK_DEPTH) {
                val style = styleLinks[current] ?: break
                val numId = styles.chain(style).fold(ParaPatch.EMPTY) { acc, s -> acc.overlay(s.pPr) }.numId ?: break
                val next = nums[numId]?.abstractId ?: break
                if (!seen.add(next)) break
                if (abstracts[next]?.isNotEmpty() == true) {
                    resolved = next
                    break
                }
                current = next
            }
            resolved?.let { target[start] = it }
        }
        if (target.isEmpty()) return
        for ((numId, inst) in nums.entries.toList()) {
            target[inst.abstractId]?.let { nums[numId] = inst.copy(abstractId = it) }
        }
    }

    private fun parseLevel(xp: XmlPullParser): NumLevel {
        var level = NumLevel()
        xp.forEachChild {
            if (xp.isW()) when (xp.name) {
                "start" -> OoxmlValues.int(xp.attr("val"))?.let { level = level.copy(start = it) }
                "numFmt" -> xp.attr("val")?.let { level = level.copy(format = it) }
                "lvlText" -> level = level.copy(text = xp.attr("val"))
                "suff" -> level = level.copy(
                    suffix = when (xp.attr("val")) {
                        "space" -> DocxLabelSuffix.SPACE
                        "nothing" -> DocxLabelSuffix.NOTHING
                        else -> DocxLabelSuffix.TAB
                    },
                )
                "isLgl" -> level = level.copy(isLegal = OoxmlValues.onOff(xp.attr("val")))
                "pPr" -> level = level.copy(pPr = parsePPr(xp).patch)
                "rPr" -> level = level.copy(rPr = parseRPr(xp).patch)
            }
        }
        return level
    }

    // ---------------------------------------------------------------- properties

    private fun parsePPr(xp: XmlPullParser): PPrResult {
        var styleId: String? = null
        var p = ParaPatch.EMPTY
        var mark = RunPatch.EMPTY
        var section: SectionInfo? = null
        xp.forEachChild {
            if (xp.isW()) when (xp.name) {
                "pStyle" -> styleId = xp.attr("val")
                "jc" -> p = p.copy(alignment = OoxmlValues.alignment(xp.attr("val")))
                "spacing" -> {
                    val before = if (xp.attr("beforeAutospacing")?.let(OoxmlValues::onOff) == true) AUTO_SPACING_PT
                    else OoxmlValues.twipsToPt(xp.attr("before"))
                    val after = if (xp.attr("afterAutospacing")?.let(OoxmlValues::onOff) == true) AUTO_SPACING_PT
                    else OoxmlValues.twipsToPt(xp.attr("after"))
                    val rule = when (xp.attr("lineRule")) {
                        "exact" -> DocxLineRule.EXACT
                        "atLeast" -> DocxLineRule.AT_LEAST
                        "auto" -> DocxLineRule.AUTO
                        else -> null
                    }
                    val line = xp.attr("line")?.trim()?.toFloatOrNull()?.takeIf { it.isFinite() }
                    p = p.copy(
                        spaceBeforePt = before ?: p.spaceBeforePt,
                        spaceAfterPt = after ?: p.spaceAfterPt,
                        lineRule = rule ?: if (line != null) DocxLineRule.AUTO else p.lineRule,
                        lineRaw = line ?: p.lineRaw,
                    )
                }
                "ind" -> {
                    val left = OoxmlValues.twipsToPt(xp.attr("left") ?: xp.attr("start"))
                    val right = OoxmlValues.twipsToPt(xp.attr("right") ?: xp.attr("end"))
                    val hanging = OoxmlValues.twipsToPt(xp.attr("hanging"))
                    val firstLine = if (hanging != null) -hanging else OoxmlValues.twipsToPt(xp.attr("firstLine"))
                    p = p.copy(
                        indentLeftPt = left ?: p.indentLeftPt,
                        indentRightPt = right ?: p.indentRightPt,
                        firstLinePt = firstLine ?: p.firstLinePt,
                    )
                }
                "keepNext" -> p = p.copy(keepNext = OoxmlValues.onOff(xp.attr("val")))
                "pageBreakBefore" -> p = p.copy(pageBreakBefore = OoxmlValues.onOff(xp.attr("val")))
                "contextualSpacing" -> p = p.copy(contextualSpacing = OoxmlValues.onOff(xp.attr("val")))
                "numPr" -> xp.forEachChild {
                    when {
                        xp.isW("ilvl") -> p = p.copy(ilvl = OoxmlValues.int(xp.attr("val")))
                        xp.isW("numId") -> p = p.copy(numId = xp.attr("val")?.trim())
                    }
                }
                "tabs" -> {
                    val tabs = ArrayList<TabPatch>()
                    xp.forEachChild {
                        if (xp.isW("tab")) {
                            val kind = xp.attr("val")?.trim()
                            val pos = OoxmlValues.twipsToPt(xp.attr("pos"))
                            if (pos != null && kind != "bar") tabs.add(TabPatch(pos, clear = kind == "clear", align = OoxmlValues.tabAlign(kind)))
                        }
                    }
                    p = p.copy(tabs = tabs)
                }
                "shd" -> p = p.copy(shading = OoxmlValues.color(xp.attr("fill")))
                "rPr" -> mark = parseRPr(xp).patch
                "sectPr" -> section = parseSectPr(xp)
            }
        }
        return PPrResult(styleId, p, mark, section)
    }

    private fun parseRPr(xp: XmlPullParser): RPrResult {
        var styleId: String? = null
        var r = RunPatch.EMPTY
        var shading: Int? = null
        xp.forEachChild {
            if (xp.isW()) {
                val v = xp.attr("val")
                when (xp.name) {
                    "rStyle" -> styleId = v
                    "b" -> r = r.copy(bold = OoxmlValues.onOff(v))
                    "i" -> r = r.copy(italic = OoxmlValues.onOff(v))
                    "u" -> r = r.copy(underline = v == null || v != "none")
                    "strike" -> r = r.copy(strike = OoxmlValues.onOff(v))
                    "dstrike" -> if (OoxmlValues.onOff(v)) r = r.copy(strike = true)
                    "caps" -> r = r.copy(caps = OoxmlValues.onOff(v))
                    "smallCaps" -> r = r.copy(smallCaps = OoxmlValues.onOff(v))
                    "vanish" -> r = r.copy(hidden = OoxmlValues.onOff(v))
                    "sz" -> r = r.copy(sizePt = OoxmlValues.halfPointsToPt(v))
                    "color" -> r = r.copy(color = OoxmlValues.color(v))
                    "highlight" -> r = r.copy(highlight = OoxmlValues.highlight(v))
                    "shd" -> shading = OoxmlValues.color(xp.attr("fill"))
                    "vertAlign" -> r = r.copy(
                        vertAlign = when (v) {
                            "superscript" -> DocxVertAlign.SUPERSCRIPT
                            "subscript" -> DocxVertAlign.SUBSCRIPT
                            else -> DocxVertAlign.BASELINE
                        },
                    )
                    "rFonts" -> r = r.copy(fontName = xp.attr("ascii") ?: xp.attr("hAnsi") ?: r.fontName)
                }
            }
        }
        // Run shading shows like a highlight; an explicit w:highlight wins.
        if (r.highlight == null && shading != null) r = r.copy(highlight = shading)
        return RPrResult(styleId, r)
    }

    private fun parseSectPr(xp: XmlPullParser): SectionInfo {
        var width: Float? = null
        var height: Float? = null
        var top: Float? = null
        var right: Float? = null
        var bottom: Float? = null
        var left: Float? = null
        var gutter = 0f
        var type: String? = null
        xp.forEachChild {
            if (xp.isW()) when (xp.name) {
                "pgSz" -> {
                    width = OoxmlValues.twipsToPt(xp.attr("w"))
                    height = OoxmlValues.twipsToPt(xp.attr("h"))
                }
                "pgMar" -> {
                    top = OoxmlValues.twipsToPt(xp.attr("top"))
                    right = OoxmlValues.twipsToPt(xp.attr("right"))
                    bottom = OoxmlValues.twipsToPt(xp.attr("bottom"))
                    left = OoxmlValues.twipsToPt(xp.attr("left"))
                    gutter = OoxmlValues.twipsToPt(xp.attr("gutter"))?.coerceAtLeast(0f) ?: 0f
                }
                "type" -> type = xp.attr("val")
            }
        }
        val d = DocxPageSetup.DEFAULT
        val w = width?.takeIf { it in MIN_PAGE_PT..MAX_PAGE_PT } ?: d.widthPt
        val h = height?.takeIf { it in MIN_PAGE_PT..MAX_PAGE_PT } ?: d.heightPt
        // Negative top/bottom margins mean "text may overlap the header"; only the size matters here.
        var mt = top?.let { kotlin.math.abs(it) } ?: d.marginTopPt
        var mb = bottom?.let { kotlin.math.abs(it) } ?: d.marginBottomPt
        var ml = (left?.coerceAtLeast(0f) ?: d.marginLeftPt) + gutter
        var mr = right?.coerceAtLeast(0f) ?: d.marginRightPt
        if (ml + mr > w - MIN_CONTENT_PT) {
            ml = w * FALLBACK_MARGIN_FRACTION
            mr = ml
        }
        if (mt + mb > h - MIN_CONTENT_PT) {
            mt = h * FALLBACK_MARGIN_FRACTION
            mb = mt
        }
        return SectionInfo(DocxPageSetup(w, h, mt, mr, mb, ml), type?.trim())
    }

    private fun parseBorders(xp: XmlPullParser): DocxTableBorders {
        var b = DocxTableBorders()
        xp.forEachChild {
            if (xp.isW()) when (xp.name) {
                "top" -> b = b.copy(top = parseBorder(xp))
                "left", "start" -> b = b.copy(left = parseBorder(xp))
                "bottom" -> b = b.copy(bottom = parseBorder(xp))
                "right", "end" -> b = b.copy(right = parseBorder(xp))
                "insideH" -> b = b.copy(insideH = parseBorder(xp))
                "insideV" -> b = b.copy(insideV = parseBorder(xp))
            }
        }
        return b
    }

    private fun parseBorder(xp: XmlPullParser): DocxBorder {
        val style = xp.attr("val")
        if (style == null || style == "none" || style == "nil") return DocxBorder.NONE
        // w:sz is in eighths of a point.
        val width = ((OoxmlValues.int(xp.attr("sz")) ?: DEFAULT_BORDER_EIGHTHS) / 8f).coerceIn(MIN_BORDER_PT, MAX_BORDER_PT)
        val color = OoxmlValues.color(xp.attr("color"))?.takeIf { it != OoxmlValues.AUTO_COLOR } ?: BLACK
        return DocxBorder(width, color)
    }

    private fun parseCellMargins(xp: XmlPullParser): CellMarginPatch {
        var m = CellMarginPatch()
        xp.forEachChild {
            if (xp.isW()) {
                val type = xp.attr("type")
                val value = when (type) {
                    null, "dxa" -> OoxmlValues.twipsToPt(xp.attr("w"))
                    "nil" -> 0f
                    else -> null
                }
                if (value != null) when (xp.name) {
                    "top" -> m = m.copy(topPt = value)
                    "left", "start" -> m = m.copy(leftPt = value)
                    "bottom" -> m = m.copy(bottomPt = value)
                    "right", "end" -> m = m.copy(rightPt = value)
                }
            }
        }
        return m
    }

    // ---------------------------------------------------------------- block content

    private fun parseBlocks(xp: XmlPullParser, out: MutableList<DocxBlock>) {
        xp.forEachChild { parseBlockElement(xp, out) }
    }

    /** Handles one block-level element (the current START_TAG). */
    private fun parseBlockElement(xp: XmlPullParser, out: MutableList<DocxBlock>) {
        tick()
        when {
            xp.isW() -> when (xp.name) {
                "p" -> {
                    var section: SectionInfo? = null
                    out.add(parseParagraph(xp) { section = it })
                    // Only body paragraphs end sections; a sectPr in a cell or note is meaningless.
                    section?.let { if (out === bodyBlocks) sectionEnds.add(SectionEnd(out.lastIndex, it)) }
                }
                "tbl" -> parseTable(xp)?.let(out::add)
                "sdt" -> xp.forEachChild { if (xp.isW("sdtContent")) parseBlocks(xp, out) }
                "customXml", "ins", "moveTo", "smartTag" -> parseBlocks(xp, out)
                "sectPr" -> if (out === bodyBlocks) bodySection = parseSectPr(xp)
            }
            xp.isMc() && xp.name == "AlternateContent" -> parseAlternateContent(xp) {
                val before = out.size
                parseBlocks(xp, out)
                out.size - before
            }
        }
    }

    /**
     * `mc:AlternateContent`: use the first `mc:Choice` that produces content we understand, else the
     * `mc:Fallback`. [produce] parses the children of the current branch element and returns how much
     * it produced.
     */
    private inline fun parseAlternateContent(xp: XmlPullParser, produce: () -> Int) {
        var satisfied = false
        xp.forEachChild {
            if (!satisfied && xp.isMc()) when (xp.name) {
                "Choice" -> if (produce() > 0) satisfied = true
                "Fallback" -> {
                    produce()
                    satisfied = true
                }
            }
        }
    }

    /**
     * Inherited formatting of a paragraph: document defaults, then (inside a table) the table
     * style's paragraph and run properties, then the paragraph style chain.
     */
    private fun paragraphContext(styleId: String?): ParaContext {
        val effective = styleId?.takeIf { styles.contains(it) } ?: styles.defaultParagraphStyleId
        var para = styles.defaultPara
        var run = styles.defaultRun
        if (tableStyles.isNotEmpty()) {
            for (s in styles.chain(tableStyles.last())) {
                para = para.overlay(s.pPr)
                run = run.overlay(s.rPr)
            }
        }
        for (s in styles.chain(effective)) {
            para = para.overlay(s.pPr)
            run = run.overlay(s.rPr)
        }
        return ParaContext(effective, para, run)
    }

    /**
     * Parses a `w:p`. A paragraph-level sectPr is reported through [onSection]; whether a page break
     * follows is decided later by [resolveSections], once the next section's type is known.
     */
    private fun parseParagraph(xp: XmlPullParser, onSection: (SectionInfo) -> Unit): DocxParagraph {
        var styleId: String? = null
        var direct = ParaPatch.EMPTY
        var mark = RunPatch.EMPTY
        var section: SectionInfo? = null
        var context: ParaContext? = null
        val builder = InlineBuilder()
        xp.forEachChild {
            if (xp.isW("pPr")) {
                val r = parsePPr(xp)
                styleId = r.styleId
                direct = r.patch
                mark = r.mark
                section = r.section
            } else {
                val c = context ?: paragraphContext(styleId).also { context = it }
                parseInline(xp, builder, c, inLink = false)
            }
        }
        val ctx = context ?: paragraphContext(styleId)

        val withoutList = ctx.stylePara.overlay(direct)
        val numId = withoutList.numId
        val ilvl = (withoutList.ilvl ?: 0).coerceIn(0, DocxNumbering.MAX_LEVELS - 1)
        val level = numbering.level(numId, ilvl)
        // Numbering indents sit between the paragraph style and direct formatting.
        val patch = if (level != null) ctx.stylePara.overlay(level.pPr).overlay(direct) else withoutList
        val markPatch = ctx.runBase.overlay(mark)
        val label = level?.let { lvl ->
            numbering.nextLabel(numId, ilvl)?.let { text ->
                DocxListLabel(text, markPatch.overlay(lvl.rPr).copy(hidden = null).toProps(), lvl.suffix)
            }
        }
        section?.let(onSection)
        return DocxParagraph(
            props = patch.toProps(ctx.styleId),
            markRun = markPatch.toProps(),
            label = label,
            items = builder.build(),
        )
    }

    /** Handles one paragraph-level element (run, hyperlink, wrappers, math). */
    private fun parseInline(xp: XmlPullParser, b: InlineBuilder, ctx: ParaContext, inLink: Boolean) {
        when {
            xp.isW() -> when (xp.name) {
                "r" -> parseRun(xp, b, ctx, inLink)
                "hyperlink" -> xp.forEachChild { parseInline(xp, b, ctx, inLink = true) }
                "ins", "smartTag", "customXml", "moveTo", "dir", "bdo", "fldSimple" ->
                    xp.forEachChild { parseInline(xp, b, ctx, inLink) }
                "sdt" -> xp.forEachChild {
                    if (xp.isW("sdtContent")) xp.forEachChild { parseInline(xp, b, ctx, inLink) }
                }
                // w:del, w:moveFrom, bookmarks, comments, proofing marks: nothing to show.
            }
            xp.isMc() && xp.name == "AlternateContent" -> parseAlternateContent(xp) {
                val before = b.changes
                xp.forEachChild { parseInline(xp, b, ctx, inLink) }
                b.changes - before
            }
            // Equations: no math layout, but keep their text readable.
            xp.isMath() && (xp.name == "oMath" || xp.name == "oMathPara") -> {
                val text = StringBuilder()
                xp.forEachDescendant { if (xp.isMath() && xp.name == "t") text.append(xp.readText()) }
                b.text(sanitize(text.toString()), ctx.runBase.toProps())
            }
        }
    }

    private fun resolveRun(ctx: ParaContext, rStyle: String?, direct: RunPatch, inLink: Boolean): RunPatch {
        var p = ctx.runBase
        for (s in styles.chain(rStyle)) p = p.overlay(s.rPr)
        p = p.overlay(direct)
        // Hyperlinks without a character style still look like links.
        if (inLink && rStyle == null) {
            p = p.copy(color = p.color ?: HYPERLINK_COLOR, underline = p.underline ?: true)
        }
        return p
    }

    private fun parseRun(xp: XmlPullParser, b: InlineBuilder, ctx: ParaContext, inLink: Boolean) {
        tick()
        val initial = resolveRun(ctx, null, RunPatch.EMPTY, inLink)
        val state = RunState(initial.toProps(), initial.hidden == true)
        xp.forEachChild { parseRunChild(xp, b, ctx, inLink, state) }
    }

    private fun parseRunChild(xp: XmlPullParser, b: InlineBuilder, ctx: ParaContext, inLink: Boolean, st: RunState) {
        if (xp.isMc() && xp.name == "AlternateContent") {
            parseAlternateContent(xp) {
                val before = b.changes
                xp.forEachChild { parseRunChild(xp, b, ctx, inLink, st) }
                b.changes - before
            }
            return
        }
        if (!xp.isW()) return
        if (xp.name == "rPr") {
            val r = parseRPr(xp)
            val resolved = resolveRun(ctx, r.styleId, r.patch, inLink)
            st.props = resolved.toProps()
            st.hidden = resolved.hidden == true
            return
        }
        if (st.hidden) return
        when (xp.name) {
            "t" -> b.text(sanitize(xp.readText()), st.props)
            "tab", "ptab" -> b.add(DocxInline.Tab(st.props))
            "br" -> b.add(if (xp.attr("type") == "page") DocxInline.PageBreak else DocxInline.LineBreak)
            "cr" -> b.add(DocxInline.LineBreak)
            "noBreakHyphen" -> b.text("‑", st.props)
            "softHyphen" -> b.text("­", st.props)
            "sym" -> symbolText(xp.attr("char"), xp.attr("font"))?.let { b.text(it, st.props) }
            "drawing" -> parseDrawing(xp)?.let(b::add)
            "pict", "object" -> parseVml(xp)?.let(b::add)
            "footnoteReference" -> xp.attr("id")?.let { b.add(noteReference(NoteKind.FOOTNOTE, it, st.props)) }
            "endnoteReference" -> xp.attr("id")?.let { b.add(noteReference(NoteKind.ENDNOTE, it, st.props)) }
            "footnoteRef", "endnoteRef" -> currentNote?.let { key ->
                noteLabels[key]?.let { b.add(DocxInline.NoteRef(it, st.props.copy(vertAlign = DocxVertAlign.SUPERSCRIPT))) }
            }
            // w:delText, w:instrText (field codes), w:fldChar, w:lastRenderedPageBreak: not shown.
        }
    }

    private fun noteReference(kind: NoteKind, id: String, run: DocxRunProps): DocxInline.NoteRef {
        val label = noteLabels.getOrPut(NoteKey(kind, id)) {
            when (kind) {
                NoteKind.FOOTNOTE -> (++footnoteCount).toString()
                NoteKind.ENDNOTE -> NumberFormats.format(++endnoteCount, "lowerRoman")
            }
        }
        return DocxInline.NoteRef(label, run.copy(vertAlign = DocxVertAlign.SUPERSCRIPT))
    }

    private fun symbolText(hex: String?, font: String?): String? {
        var code = hex?.trim()?.toIntOrNull(16) ?: return null
        // Symbol-font characters are sometimes written without the U+F000 private-use offset.
        val symbolFont = font != null && (font.contains("Symbol", true) || font.contains("Wingdings", true))
        if (symbolFont && code in 0x20..0xFF) code += 0xF000
        if (!Character.isValidCodePoint(code) || code < 0x20) return null
        return NumberFormats.mapSymbolChars(String(Character.toChars(code)))
    }

    private fun parseDrawing(xp: XmlPullParser): DocxInline.Image? {
        var extentW: Float? = null
        var extentH: Float? = null
        var xfrmW: Float? = null
        var xfrmH: Float? = null
        var relId: String? = null
        var crop: FloatArray? = null
        xp.forEachDescendant {
            when (xp.name) {
                "extent" -> if (extentW == null) {
                    extentW = OoxmlValues.emuToPt(xp.attr("cx"))
                    extentH = OoxmlValues.emuToPt(xp.attr("cy"))
                }
                "ext" -> if (xfrmW == null && xp.attr("cx") != null) {
                    xfrmW = OoxmlValues.emuToPt(xp.attr("cx"))
                    xfrmH = OoxmlValues.emuToPt(xp.attr("cy"))
                }
                "blip" -> if (relId == null) relId = xp.attr("embed")
                // a:srcRect inside pic:blipFill: l/t/r/b in 1/1000 percent of the source picture.
                "srcRect" -> if (crop == null) {
                    crop = floatArrayOf(
                        OoxmlValues.cropFraction(xp.attr("l"), drawingMl = true) ?: 0f,
                        OoxmlValues.cropFraction(xp.attr("t"), drawingMl = true) ?: 0f,
                        OoxmlValues.cropFraction(xp.attr("r"), drawingMl = true) ?: 0f,
                        OoxmlValues.cropFraction(xp.attr("b"), drawingMl = true) ?: 0f,
                    )
                }
            }
        }
        return image(relId, extentW ?: xfrmW, extentH ?: xfrmH, crop)
    }

    /** Legacy VML pictures (`w:pict`, `w:object`), common in mc:Fallback branches and older files. */
    private fun parseVml(xp: XmlPullParser): DocxInline.Image? {
        var width: Float? = null
        var height: Float? = null
        var relId: String? = null
        var crop: FloatArray? = null
        xp.forEachDescendant {
            when (xp.name) {
                "shape", "rect", "roundrect", "image" -> if (width == null) {
                    xp.attr("style")?.split(';')?.forEach { decl ->
                        val key = decl.substringBefore(':').trim().lowercase()
                        val value = decl.substringAfter(':', "")
                        when (key) {
                            "width" -> width = OoxmlValues.cssLengthToPt(value)
                            "height" -> height = OoxmlValues.cssLengthToPt(value)
                        }
                    }
                }
                "imagedata" -> if (relId == null) {
                    relId = xp.attr("id") ?: xp.attr("relid")
                    crop = floatArrayOf(
                        OoxmlValues.cropFraction(xp.attr("cropleft"), drawingMl = false) ?: 0f,
                        OoxmlValues.cropFraction(xp.attr("croptop"), drawingMl = false) ?: 0f,
                        OoxmlValues.cropFraction(xp.attr("cropright"), drawingMl = false) ?: 0f,
                        OoxmlValues.cropFraction(xp.attr("cropbottom"), drawingMl = false) ?: 0f,
                    )
                }
            }
        }
        return image(relId, width, height, crop)
    }

    /** [crop] is left, top, right, bottom as fractions of the source picture (negative = padding). */
    private fun image(relId: String?, widthPt: Float?, heightPt: Float?, crop: FloatArray?): DocxInline.Image? {
        val rel = rels[relId ?: return null] ?: return null
        if (rel.external) return null
        val w = widthPt?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val h = heightPt?.takeIf { it.isFinite() && it > 0f } ?: 0f
        val (l, r) = sanitizeCrop(crop?.get(0) ?: 0f, crop?.get(2) ?: 0f)
        val (t, b) = sanitizeCrop(crop?.get(1) ?: 0f, crop?.get(3) ?: 0f)
        return DocxInline.Image(rel.target, w, h, cropLeft = l, cropTop = t, cropRight = r, cropBottom = b)
    }

    // ---------------------------------------------------------------- tables

    private fun parseTable(xp: XmlPullParser): DocxTable? {
        var styleId: String? = null
        var directBorders = DocxTableBorders()
        var directMargins = CellMarginPatch()
        var indent = 0f
        var alignment: DocxAlignment? = null
        val grid = ArrayList<Float>()
        val rows = ArrayList<DocxTableRow>()
        // The schema puts tblPr first, so the style is known before the first row; it is pushed then
        // and applies to every paragraph in the table's cells.
        var styleFrame = -1
        try {
            xp.forEachChild {
                when {
                    xp.isW("tblPr") -> xp.forEachChild {
                        if (xp.isW()) when (xp.name) {
                            "tblStyle" -> styleId = xp.attr("val")
                            "tblBorders" -> directBorders = parseBorders(xp)
                            "tblCellMar" -> directMargins = parseCellMargins(xp)
                            "tblInd" -> if (xp.attr("type").let { it == null || it == "dxa" }) {
                                indent = OoxmlValues.twipsToPt(xp.attr("w")) ?: 0f
                            }
                            "jc" -> alignment = OoxmlValues.alignment(xp.attr("val"))
                        }
                    }
                    xp.isW("tblGrid") -> xp.forEachChild {
                        if (xp.isW("gridCol")) grid.add((OoxmlValues.twipsToPt(xp.attr("w")) ?: 0f).coerceAtLeast(0f))
                    }
                    else -> {
                        if (styleFrame < 0) {
                            tableStyles.add(effectiveTableStyle(styleId))
                            styleFrame = tableStyles.lastIndex
                        }
                        collectRows(xp, rows)
                    }
                }
            }
        } finally {
            if (styleFrame >= 0) while (tableStyles.size > styleFrame) tableStyles.removeAt(tableStyles.lastIndex)
        }
        if (rows.isEmpty()) return null
        var borders = DocxTableBorders()
        var margins = CellMarginPatch()
        for (s in styles.chain(effectiveTableStyle(styleId))) {
            borders = borders.overlay(s.tableBorders)
            margins = margins.overlay(s.cellMargins)
        }
        return DocxTable(
            gridPt = grid,
            rows = rows,
            indentPt = indent.coerceIn(-MAX_TABLE_INDENT_PT, MAX_TABLE_INDENT_PT),
            alignment = alignment ?: DocxAlignment.LEFT,
            borders = borders.overlay(directBorders),
            cellMargins = margins.overlay(directMargins).toMargins(),
        )
    }

    private fun effectiveTableStyle(styleId: String?): String? =
        styleId?.takeIf { styles.contains(it) } ?: styles.defaultTableStyleId

    private fun collectRows(xp: XmlPullParser, rows: MutableList<DocxTableRow>) {
        when {
            xp.isW("tr") -> rows.add(parseRow(xp))
            xp.isW("sdt") -> xp.forEachChild {
                if (xp.isW("sdtContent")) xp.forEachChild { collectRows(xp, rows) }
            }
            xp.isW("customXml") || xp.isW("ins") -> xp.forEachChild { collectRows(xp, rows) }
        }
    }

    private fun parseRow(xp: XmlPullParser): DocxTableRow {
        checkpoint()
        var row = DocxTableRow(cells = emptyList())
        val cells = ArrayList<DocxTableCell>()
        xp.forEachChild {
            when {
                xp.isW("trPr") -> xp.forEachChild {
                    if (xp.isW()) when (xp.name) {
                        "gridBefore" -> row = row.copy(gridBefore = (OoxmlValues.int(xp.attr("val")) ?: 0).coerceIn(0, MAX_GRID_SPAN))
                        "trHeight" -> row = row.copy(
                            heightPt = (OoxmlValues.twipsToPt(xp.attr("val")) ?: 0f).coerceIn(0f, MAX_PAGE_PT),
                            exactHeight = xp.attr("hRule") == "exact",
                        )
                        "tblHeader" -> row = row.copy(isHeader = OoxmlValues.onOff(xp.attr("val")))
                    }
                }
                else -> collectCells(xp, cells)
            }
        }
        return row.copy(cells = cells)
    }

    private fun collectCells(xp: XmlPullParser, cells: MutableList<DocxTableCell>) {
        when {
            xp.isW("tc") -> cells.add(parseCell(xp))
            xp.isW("sdt") -> xp.forEachChild {
                if (xp.isW("sdtContent")) xp.forEachChild { collectCells(xp, cells) }
            }
            xp.isW("customXml") -> xp.forEachChild { collectCells(xp, cells) }
        }
    }

    private fun parseCell(xp: XmlPullParser): DocxTableCell {
        var cell = DocxTableCell(blocks = emptyList())
        val blocks = ArrayList<DocxBlock>()
        xp.forEachChild {
            if (xp.isW("tcPr")) {
                xp.forEachChild {
                    if (xp.isW()) when (xp.name) {
                        "gridSpan" -> cell = cell.copy(gridSpan = (OoxmlValues.int(xp.attr("val")) ?: 1).coerceIn(1, MAX_GRID_SPAN))
                        "vMerge" -> cell = cell.copy(
                            vMerge = if (xp.attr("val") == "restart") DocxVMerge.RESTART else DocxVMerge.CONTINUE,
                        )
                        "shd" -> cell = cell.copy(shading = OoxmlValues.color(xp.attr("fill"))?.takeIf { it != OoxmlValues.AUTO_COLOR })
                        "vAlign" -> cell = cell.copy(
                            vAlign = when (xp.attr("val")) {
                                "center" -> DocxCellVAlign.CENTER
                                "bottom" -> DocxCellVAlign.BOTTOM
                                else -> DocxCellVAlign.TOP
                            },
                        )
                        "tcBorders" -> {
                            val b = parseBorders(xp)
                            cell = cell.copy(borders = DocxCellBorders(b.top, b.left, b.bottom, b.right))
                        }
                    }
                }
            } else {
                parseBlockElement(xp, blocks)
            }
        }
        return cell.copy(blocks = blocks)
    }

    // ---------------------------------------------------------------- foot/endnotes

    private fun readNotes(): List<DocxNote> {
        val content = HashMap<NoteKey, List<DocxBlock>>()
        val sources = listOf(
            NoteKind.FOOTNOTE to partOfType("footnotes", "word/footnotes.xml"),
            NoteKind.ENDNOTE to partOfType("endnotes", "word/endnotes.xml"),
        )
        // Both part names are resolved above, before `rels` is swapped below.
        val mainRels = rels
        for ((kind, part) in sources) {
            if (noteLabels.keys.none { it.kind == kind }) continue
            // Relationship ids are per part: pictures in notes resolve against the notes part's rels.
            rels = readRelationships(part)
            try {
                readOptionalPart(part) { xp -> parseNotesPart(xp, kind, content) }
            } finally {
                rels = mainRels
                currentNote = null
            }
        }
        return noteLabels.mapNotNull { (key, label) ->
            val blocks = content[key]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            DocxNote(label, withLeadingLabel(blocks, label))
        }
    }

    private fun parseNotesPart(xp: XmlPullParser, kind: NoteKind, out: MutableMap<NoteKey, List<DocxBlock>>) {
        xp.forEachChild {
            if (xp.isW("footnote") || xp.isW("endnote")) {
                val key = xp.attr("id")?.let { NoteKey(kind, it) }
                val type = xp.attr("type")
                val isContent = type == null || type == "normal"
                if (key != null && isContent && key in noteLabels) {
                    currentNote = key
                    val blocks = ArrayList<DocxBlock>()
                    parseBlocks(xp, blocks)
                    out[key] = blocks
                    currentNote = null
                }
            }
        }
    }

    /** Makes sure a note starts with its reference mark, even if the document omitted w:footnoteRef. */
    private fun withLeadingLabel(blocks: List<DocxBlock>, label: String): List<DocxBlock> {
        val first = blocks.first()
        if (first is DocxParagraph && first.items.any { it is DocxInline.NoteRef }) return blocks
        val run = (first as? DocxParagraph)?.markRun ?: DocxRunProps()
        val mark = DocxInline.NoteRef(label, run.copy(vertAlign = DocxVertAlign.SUPERSCRIPT))
        return if (first is DocxParagraph) {
            listOf(first.copy(items = listOf(mark, DocxInline.Text(" ", first.markRun)) + first.items)) + blocks.drop(1)
        } else {
            listOf(DocxParagraph(DocxParagraphProps(), run, null, listOf(mark))) + blocks
        }
    }

    companion object {
        const val INVALID_DOCUMENT_MESSAGE = "This file isn't a valid Word document."
        const val LEGACY_OR_ENCRYPTED_MESSAGE =
            "This Word file is password-protected or uses the old .doc format, which isn't supported."

        private const val DEFAULT_MAIN_PART = "word/document.xml"
        private const val CHECKPOINT_INTERVAL = 256
        private const val MAX_STYLE_LINK_DEPTH = 8

        /** Largest decompressed XML part accepted (zip-bomb guard); real documents stay far below. */
        internal const val MAX_PART_BYTES = 128L * 1024 * 1024

        /** Crops leaving less than this fraction of the picture are ignored as broken. */
        private const val MIN_VISIBLE_FRACTION = 0.01f
        private const val FEATURE_RELAXED = "http://xmlpull.org/v1/doc/features.html#relaxed"
        private const val AUTO_SPACING_PT = 14f
        private const val MIN_PAGE_PT = 72f
        private const val MAX_PAGE_PT = 5000f
        private const val MIN_CONTENT_PT = 72f
        private const val FALLBACK_MARGIN_FRACTION = 0.1f
        private const val DEFAULT_BORDER_EIGHTHS = 4
        private const val MIN_BORDER_PT = 0.5f
        private const val MAX_BORDER_PT = 6f
        private const val MAX_GRID_SPAN = 63
        private const val MAX_TABLE_INDENT_PT = 720f
        private val BLACK = OoxmlValues.opaque(0x000000)
        private val HYPERLINK_COLOR = OoxmlValues.opaque(0x0563C1)

        /** OLE2 compound file signature: legacy .doc files and password-encrypted .docx files. */
        private val OLE_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte())

        /**
         * Parses [file]. Throws [DocxConversionException] with a user-presentable message when the
         * file isn't a readable Word document.
         */
        fun parse(file: File, checkpoint: () -> Unit = {}): DocxDocument {
            val header = try {
                file.inputStream().use { input -> ByteArray(4).also { if (input.read(it) < 4) throw invalid() } }
            } catch (e: IOException) {
                throw DocxConversionException("This file couldn't be read.", e)
            }
            if (header.contentEquals(OLE_SIGNATURE)) throw DocxConversionException(LEGACY_OR_ENCRYPTED_MESSAGE)
            val zip = try {
                ZipFile(file)
            } catch (e: ZipException) {
                throw invalid(e)
            } catch (e: IOException) {
                throw invalid(e)
            }
            return zip.use { parse(it.asDocxParts(), checkpoint) }
        }

        /**
         * Parses a package given as raw parts (also used by tests with in-memory XML). [checkpoint]
         * is called regularly and may throw (e.g. CancellationException) to abandon parsing.
         */
        fun parse(parts: DocxParts, checkpoint: () -> Unit = {}): DocxDocument = try {
            DocxParser(parts, checkpoint).parseDocument()
        } catch (e: IOException) {
            // Damaged zip entries surface as ZipException (an IOException) while reading parts.
            throw invalid(e)
        }

        /**
         * Name of the package's main document part, or null when there is none: the target of the
         * root officeDocument relationship (e.g. "word/document2.xml"), else "word/document.xml".
         * Lookups go through [parts], so a case-insensitive [asDocxParts] view is honoured. Callers
         * that only need to recognise a Word package can use this to agree with the converter.
         */
        fun findMainDocumentPart(parts: DocxParts): String? = DocxParser(parts) {}.mainDocumentPartOrNull()

        /** Clamps a pair of opposite crop fractions; a crop that leaves (almost) nothing is dropped. */
        internal fun sanitizeCrop(start: Float, end: Float): Pair<Float, Float> {
            val a = if (start.isFinite()) start.coerceIn(-1f, 1f) else 0f
            val b = if (end.isFinite()) end.coerceIn(-1f, 1f) else 0f
            return if (1f - a - b < MIN_VISIBLE_FRACTION || 1f - max(0f, a) - max(0f, b) < MIN_VISIBLE_FRACTION) 0f to 0f else a to b
        }

        private fun invalid(cause: Throwable? = null) = DocxConversionException(INVALID_DOCUMENT_MESSAGE, cause)

        /** Resolves a relationship target against the source part's folder ("word/"). */
        internal fun resolvePartName(baseDir: String, target: String): String {
            val decoded = percentDecode(target.replace('\\', '/'))
            val combined = if (decoded.startsWith("/")) decoded.drop(1) else baseDir + decoded
            val segments = ArrayList<String>()
            for (segment in combined.split('/')) {
                when (segment) {
                    "", "." -> Unit
                    ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                    else -> segments.add(segment)
                }
            }
            return segments.joinToString("/")
        }

        private fun percentDecode(s: String): String {
            if ('%' !in s) return s
            val bytes = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                val hex = if (c == '%' && i + 2 <= s.lastIndex) s.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                } else {
                    bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                    i++
                }
            }
            return bytes.toString(Charsets.UTF_8.name())
        }

        /** Removes control characters that would confuse layout; newlines inside text act as spaces. */
        private fun sanitize(s: String): String {
            if (s.none { (it < ' ' && it != '\t') || it == '￼' }) return s
            return buildString(s.length) {
                for (c in s) {
                    when {
                        c == '\n' || c == '\r' -> append(' ')
                        c == '\t' -> append(c)
                        c < ' ' || c == '￼' -> Unit
                        else -> append(c)
                    }
                }
            }
        }
    }
}
