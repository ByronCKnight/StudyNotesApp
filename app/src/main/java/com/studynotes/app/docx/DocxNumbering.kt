package com.studynotes.app.docx

/** One list level (`w:lvl`) of an abstract numbering definition. */
internal data class NumLevel(
    val start: Int = 1,
    /** `w:numFmt` value, e.g. "decimal", "lowerLetter", "bullet". */
    val format: String = "decimal",
    /** `w:lvlText`, e.g. "%1." or "•"; null when absent. */
    val text: String? = null,
    val suffix: DocxLabelSuffix = DocxLabelSuffix.TAB,
    /** `w:isLgl`: show every referenced level as a decimal number. */
    val isLegal: Boolean = false,
    val pPr: ParaPatch = ParaPatch.EMPTY,
    val rPr: RunPatch = RunPatch.EMPTY,
)

/** A `w:lvlOverride` inside a `w:num`. */
internal data class LevelOverride(val startOverride: Int? = null, val level: NumLevel? = null)

/** A `w:num`: a numbering instance pointing at an abstract definition. */
internal data class NumInstance(val abstractId: String, val overrides: Map<Int, LevelOverride> = emptyMap())

/**
 * List numbering state for one document.
 *
 * Counters follow Word: they belong to the *abstract* definition, so several `w:num` instances that
 * share an abstract list continue one sequence, and a `w:startOverride` restarts the level the
 * first time its instance is used (this is how Word encodes "Restart numbering"). Using a level
 * restarts every deeper level.
 */
internal class DocxNumbering(
    private val abstracts: Map<String, Map<Int, NumLevel>>,
    private val nums: Map<String, NumInstance>,
) {
    private class Counters {
        val values = IntArray(MAX_LEVELS)
        val started = BooleanArray(MAX_LEVELS)
    }

    private val counters = HashMap<String, Counters>()
    private val usedInstanceLevels = HashSet<Pair<String, Int>>()

    /** The effective level definition, or null when the list or level doesn't exist. */
    fun level(numId: String?, ilvl: Int): NumLevel? {
        if (numId == null || numId == "0" || ilvl !in 0 until MAX_LEVELS) return null
        val inst = nums[numId] ?: return null
        return inst.overrides[ilvl]?.level ?: abstracts[inst.abstractId]?.get(ilvl)
    }

    /**
     * Advances the counter for ([numId], [ilvl]) and returns the formatted label ("2.", "b)", "•"),
     * or null when the paragraph isn't really in a list. Call once per list paragraph, in document
     * order.
     */
    fun nextLabel(numId: String?, ilvl: Int): String? {
        val lvl = level(numId, ilvl) ?: return null
        val inst = nums[numId] ?: return null
        val levels = abstracts[inst.abstractId].orEmpty()
        val state = counters.getOrPut(inst.abstractId) { Counters() }

        if (usedInstanceLevels.add(numId!! to ilvl)) {
            inst.overrides[ilvl]?.let { ov ->
                val restart = ov.startOverride ?: ov.level?.start
                if (restart != null) {
                    state.values[ilvl] = restart - 1
                    state.started[ilvl] = true
                }
            }
        }
        if (state.started[ilvl]) {
            state.values[ilvl]++
        } else {
            state.values[ilvl] = lvl.start
            state.started[ilvl] = true
        }
        for (deeper in ilvl + 1 until MAX_LEVELS) state.started[deeper] = false

        val template = lvl.text ?: return null
        if (lvl.format == "bullet") return NumberFormats.mapSymbolChars(template).ifEmpty { null }
        val label = buildString {
            var i = 0
            while (i < template.length) {
                val c = template[i]
                val next = template.getOrNull(i + 1)
                if (c == '%' && next != null && next in '1'..'9') {
                    val refLevel = next - '1'
                    val refDef = inst.overrides[refLevel]?.level ?: levels[refLevel]
                    val value = if (state.started[refLevel]) state.values[refLevel] else refDef?.start ?: 1
                    val format = if (lvl.isLegal && refLevel < ilvl) "decimal" else refDef?.format ?: "decimal"
                    append(NumberFormats.format(value, format))
                    i += 2
                } else {
                    append(c)
                    i++
                }
            }
        }
        return NumberFormats.mapSymbolChars(label).ifEmpty { null }
    }

    companion object {
        const val MAX_LEVELS = 9
        val EMPTY = DocxNumbering(emptyMap(), emptyMap())
    }
}

/** Number formats used by list labels and note references. Pure functions. */
internal object NumberFormats {

    fun format(n: Int, format: String): String = when (format) {
        "decimal" -> n.toString()
        "decimalZero" -> if (n in 0..9) "0$n" else n.toString()
        "lowerLetter" -> letters(n, 'a')
        "upperLetter" -> letters(n, 'A')
        "lowerRoman" -> roman(n).lowercase()
        "upperRoman" -> roman(n)
        "ordinal" -> ordinal(n)
        "none", "bullet" -> ""
        else -> n.toString()
    }

    /** Word's lettering: a..z, then aa..zz, aaa.. (the letter repeats). */
    fun letters(n: Int, first: Char): String {
        if (n <= 0) return n.toString()
        val repeat = (n - 1) / 26 + 1
        val letter = first + (n - 1) % 26
        return letter.toString().repeat(repeat.coerceAtMost(MAX_LETTER_REPEAT))
    }

    fun roman(n: Int): String {
        if (n !in 1..3999) return n.toString()
        val sb = StringBuilder()
        var rest = n
        for ((value, symbol) in ROMAN) {
            while (rest >= value) {
                sb.append(symbol)
                rest -= value
            }
        }
        return sb.toString()
    }

    private fun ordinal(n: Int): String {
        val suffix = when {
            n % 100 in 11..13 -> "th"
            n % 10 == 1 -> "st"
            n % 10 == 2 -> "nd"
            n % 10 == 3 -> "rd"
            else -> "th"
        }
        return "$n$suffix"
    }

    /**
     * Bullets in Symbol/Wingdings fonts are stored as private-use code points (U+F0xx) that no
     * Unicode font can draw. Map the common ones to real characters and any other to a bullet.
     */
    fun mapSymbolChars(text: String): String {
        if (text.none { it in ''..'' }) return text
        return buildString {
            for (c in text) {
                append(if (c in ''..'') SYMBOL_MAP[c.code and 0xFF] ?: '•' else c)
            }
        }
    }

    private const val MAX_LETTER_REPEAT = 10

    private val ROMAN = listOf(
        1000 to "M", 900 to "CM", 500 to "D", 400 to "CD", 100 to "C", 90 to "XC",
        50 to "L", 40 to "XL", 10 to "X", 9 to "IX", 5 to "V", 4 to "IV", 1 to "I",
    )

    private val SYMBOL_MAP = mapOf(
        0xB7 to '•', // Symbol bullet
        0xA7 to '▪', // Wingdings small square
        0x6E to '■', // Wingdings black square
        0x71 to '❑', // Wingdings shadowed square
        0x6C to '●', // Wingdings black circle
        0x75 to '◆', // Wingdings diamond
        0x76 to '❖', // Wingdings diamond-minus-x
        0xD8 to '➢', // Wingdings arrowhead
        0xE0 to '→', // Wingdings arrow
        0xFC to '✓', // Wingdings check mark
        0x2D to '–', // Symbol dash
    )
}
