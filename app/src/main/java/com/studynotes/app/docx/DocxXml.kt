package com.studynotes.app.docx

import org.xmlpull.v1.XmlPullParser

/** Namespaces the parser cares about (transitional and strict OOXML flavours). */
internal object OoxmlNs {
    const val W = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    const val W_STRICT = "http://purl.oclc.org/ooxml/wordprocessingml/main"
    const val MC = "http://schemas.openxmlformats.org/markup-compatibility/2006"
    const val MATH = "http://schemas.openxmlformats.org/officeDocument/2006/math"
    const val MATH_STRICT = "http://purl.oclc.org/ooxml/officeDocument/math"
}

/** True when the current element is in the WordprocessingML namespace. */
internal fun XmlPullParser.isW(): Boolean = namespace == OoxmlNs.W || namespace == OoxmlNs.W_STRICT

internal fun XmlPullParser.isMc(): Boolean = namespace == OoxmlNs.MC

internal fun XmlPullParser.isMath(): Boolean = namespace == OoxmlNs.MATH || namespace == OoxmlNs.MATH_STRICT

/** True when the current element is `w:<localName>`. */
internal fun XmlPullParser.isW(localName: String): Boolean = isW() && name == localName

/**
 * Calls [onChild] for every direct child element of the element whose START_TAG is current, with the
 * parser positioned on the child's START_TAG. A handler may consume the child (ending on its END_TAG)
 * or leave it untouched; whatever it leaves is skipped, so unknown elements never derail parsing.
 * Returns with the parser on the parent's END_TAG.
 */
internal inline fun XmlPullParser.forEachChild(onChild: () -> Unit) {
    val parentDepth = depth
    while (true) {
        when (next()) {
            XmlPullParser.END_DOCUMENT -> return
            XmlPullParser.END_TAG -> if (depth == parentDepth) return
            XmlPullParser.START_TAG -> {
                val childDepth = depth
                onChild()
                if (!(eventType == XmlPullParser.END_TAG && depth == childDepth)) skipTo(childDepth)
            }
        }
    }
}

/**
 * Calls [onStart] for every START_TAG nested anywhere inside the current element. The handler must
 * not move the parser. Returns with the parser on the element's END_TAG.
 */
internal inline fun XmlPullParser.forEachDescendant(onStart: () -> Unit) {
    val rootDepth = depth
    while (true) {
        when (next()) {
            XmlPullParser.END_DOCUMENT -> return
            XmlPullParser.END_TAG -> if (depth == rootDepth) return
            XmlPullParser.START_TAG -> onStart()
        }
    }
}

/** Advances to the END_TAG of the element at [elementDepth] (the current one or an ancestor). */
internal fun XmlPullParser.skipTo(elementDepth: Int) {
    if (eventType == XmlPullParser.END_TAG && depth == elementDepth) return
    while (true) {
        when (next()) {
            XmlPullParser.END_DOCUMENT -> return
            XmlPullParser.END_TAG -> if (depth == elementDepth) return
        }
    }
}

/** The concatenated character data of the current element (including descendants). */
internal fun XmlPullParser.readText(): String {
    val rootDepth = depth
    val sb = StringBuilder()
    while (true) {
        when (next()) {
            XmlPullParser.END_DOCUMENT -> return sb.toString()
            XmlPullParser.TEXT -> sb.append(text)
            XmlPullParser.END_TAG -> if (depth == rootDepth) return sb.toString()
        }
    }
}

/**
 * Attribute by local name in any namespace. WordprocessingML qualifies its attributes (`w:val`),
 * relationship ids use `r:`, and some producers write them unqualified; matching on the local name
 * accepts all of them.
 */
internal fun XmlPullParser.attr(localName: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i) == localName) return getAttributeValue(i)
    }
    return null
}

/**
 * Lenient conversions of OOXML attribute values. Every function returns null (or a default) on bad
 * input instead of throwing: a document with one odd attribute should still open.
 */
internal object OoxmlValues {
    /** `ST_OnOff`: absent attribute means on. */
    fun onOff(value: String?): Boolean = when (value?.trim()?.lowercase()) {
        null, "", "1", "true", "on" -> true
        "0", "false", "off", "none" -> false
        else -> true
    }

    /**
     * A twips measure (`ST_TwipsMeasure` / `ST_SignedTwipsMeasure`), also accepting the universal
     * measures strict documents use ("12pt", "1in", "2.54cm", "25.4mm", "1pc"), in points.
     */
    fun twipsToPt(value: String?): Float? = measureToPt(value, unitlessPerPt = 20f)

    /** Half-points (`w:sz`) or a universal measure, in points. */
    fun halfPointsToPt(value: String?): Float? = measureToPt(value, unitlessPerPt = 2f)

    /** EMU (English Metric Units, 12700 per point) to points. */
    fun emuToPt(value: String?): Float? = value?.trim()?.toDoubleOrNull()?.let { (it / 12700.0).toFloat() }

    private fun measureToPt(value: String?, unitlessPerPt: Float): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        val unit = UNITS.firstOrNull { v.endsWith(it.first) }
        val number = (if (unit != null) v.dropLast(unit.first.length) else v).trim().toDoubleOrNull() ?: return null
        if (number.isNaN() || number.isInfinite()) return null
        val pt = if (unit != null) number * unit.second else number / unitlessPerPt
        return pt.toFloat()
    }

    private val UNITS = listOf("pt" to 1.0, "in" to 72.0, "cm" to 72.0 / 2.54, "mm" to 72.0 / 25.4, "pc" to 12.0, "pi" to 12.0)

    /** CSS-like VML length ("120pt", "1.5in", "200px", "200"): unitless values are pixels at 96 dpi. */
    fun cssLengthToPt(value: String?): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.endsWith("px")) return v.dropLast(2).trim().toFloatOrNull()?.times(0.75f)
        v.toFloatOrNull()?.let { return it * 0.75f }
        return measureToPt(v, unitlessPerPt = 1f)
    }

    fun int(value: String?): Int? = value?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

    /** "RRGGBB" to an opaque colour; "auto" maps to [AUTO_COLOR]; invalid values to null. */
    fun color(value: String?): Int? {
        val v = value?.trim() ?: return null
        if (v.equals("auto", ignoreCase = true)) return AUTO_COLOR
        if (v.length != 6) return null
        val rgb = v.toIntOrNull(16) ?: return null
        return opaque(rgb)
    }

    /**
     * Sentinel meaning "automatic / none" inside patches, so an explicit `auto` can override an
     * inherited colour. Fully transparent black is never a real document colour.
     */
    const val AUTO_COLOR = 0

    /** `w:highlight` names. "none" maps to [AUTO_COLOR]. */
    fun highlight(name: String?): Int? = when (name?.trim()) {
        null -> null
        "none" -> AUTO_COLOR
        "yellow" -> opaque(0xFFFF00)
        "green" -> opaque(0x00FF00)
        "cyan" -> opaque(0x00FFFF)
        "magenta" -> opaque(0xFF00FF)
        "blue" -> opaque(0x0000FF)
        "red" -> opaque(0xFF0000)
        "darkBlue" -> opaque(0x000080)
        "darkCyan" -> opaque(0x008080)
        "darkGreen" -> opaque(0x008000)
        "darkMagenta" -> opaque(0x800080)
        "darkRed" -> opaque(0x800000)
        "darkYellow" -> opaque(0x808000)
        "darkGray" -> opaque(0x808080)
        "lightGray" -> opaque(0xC0C0C0)
        "black" -> opaque(0x000000)
        "white" -> opaque(0xFFFFFF)
        else -> null
    }

    fun alignment(value: String?): DocxAlignment? = when (value?.trim()) {
        "left", "start" -> DocxAlignment.LEFT
        "center" -> DocxAlignment.CENTER
        "right", "end" -> DocxAlignment.RIGHT
        "both", "distribute", "justify", "lowKashida", "mediumKashida", "highKashida", "thaiDistribute" -> DocxAlignment.JUSTIFY
        else -> null
    }

    /** `w:tab/@w:val`; list ("num"), unknown and missing values act as left stops. */
    fun tabAlign(value: String?): DocxTabAlign = when (value?.trim()) {
        "right", "end" -> DocxTabAlign.RIGHT
        "center" -> DocxTabAlign.CENTER
        "decimal" -> DocxTabAlign.DECIMAL
        else -> DocxTabAlign.LEFT
    }

    /**
     * A picture crop fraction: DrawingML `a:srcRect` values are 1/1000 percent ("12500" = 12.5 %),
     * VML `cropleft` etc. are fractions ("0.125") or 16.16 fixed point with an "f" suffix ("8192f").
     */
    fun cropFraction(value: String?, drawingMl: Boolean): Float? {
        val v = value?.trim()?.lowercase() ?: return null
        if (v.isEmpty()) return null
        val f = when {
            drawingMl -> v.toDoubleOrNull()?.div(100_000.0)
            v.endsWith("f") -> v.dropLast(1).trim().toDoubleOrNull()?.div(65_536.0)
            else -> v.toDoubleOrNull()
        } ?: return null
        return f.toFloat().takeIf { it.isFinite() }
    }

    fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0xFFFFFF)
}
