package com.studynotes.app.export

import kotlin.math.max
import kotlin.math.min

/**
 * Affine map from page space (the page as PdfRenderer displays it: crop box with /Rotate applied,
 * origin top-left, y down, units = points) to the page's default PDF user space (origin bottom-left,
 * y up, unrotated). Concatenated onto a content stream's CTM it lets the exporter draw annotations
 * with their stored page-space coordinates.
 *
 * Mapping: x' = a·x + c·y + e, y' = b·x + d·y + f — the operand order of the PDF `cm` operator.
 * Every variant is a quarter-turn plus a mirror, so widths and lengths are preserved (|det| = 1).
 */
internal data class PdfSpaceTransform(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val e: Float,
    val f: Float,
    /** Page width in page space: the crop box width, or its height when rotated by 90°/270°. */
    val pageWidth: Float,
    /** Page height in page space. */
    val pageHeight: Float,
) {
    fun mapX(x: Float, y: Float): Float = a * x + c * y + e

    fun mapY(x: Float, y: Float): Float = b * x + d * y + f

    /** The six coefficients in PDF matrix order (a b c d e f). */
    val coefficients: FloatArray get() = floatArrayOf(a, b, c, d, e, f)

    /**
     * User-space bounds [llx, lly, urx, ury] of a page-space rectangle. Mapping two opposite corners
     * is enough because the transform never shears.
     */
    fun mapRect(left: Float, top: Float, right: Float, bottom: Float): FloatArray {
        val x1 = mapX(left, top)
        val y1 = mapY(left, top)
        val x2 = mapX(right, bottom)
        val y2 = mapY(right, bottom)
        return floatArrayOf(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /**
     * User-space rectangle [llx, lly, urx, ury] for a square comment icon of [size] points placed
     * [gap] points to the right of a note card's top-right corner ([cardRight], [cardTop] in page
     * space). The square is kept on the page, so a card at the right edge gets the icon over its corner.
     */
    fun commentIconRect(cardRight: Float, cardTop: Float, size: Float, gap: Float): FloatArray {
        val left = (cardRight + gap).coerceAtMost(pageWidth - size).coerceAtLeast(0f)
        val top = cardTop.coerceAtMost(pageHeight - size).coerceAtLeast(0f)
        return mapRect(left, top, left + size, top + size)
    }

    companion object {
        /**
         * Transform for a page whose crop box (already clipped to the media box) is
         * ([llx], [lly], [urx], [ury]) in user space and whose /Rotate is [rotation].
         *
         * | /Rotate | user (x', y') from page (x, y) |
         * |---|---|
         * | 0   | (llx + x, ury - y) |
         * | 90  | (llx + y, lly + x) |
         * | 180 | (urx - x, lly + y) |
         * | 270 | (urx - y, ury - x) |
         */
        fun displayToPdf(rotation: Int, llx: Float, lly: Float, urx: Float, ury: Float): PdfSpaceTransform {
            // Normalise in case a producer wrote the corners in the wrong order.
            val x0 = min(llx, urx)
            val x1 = max(llx, urx)
            val y0 = min(lly, ury)
            val y1 = max(lly, ury)
            val width = x1 - x0
            val height = y1 - y0
            return when (normalizeRotation(rotation)) {
                90 -> PdfSpaceTransform(0f, 1f, 1f, 0f, x0, y0, pageWidth = height, pageHeight = width)
                180 -> PdfSpaceTransform(-1f, 0f, 0f, 1f, x1, y0, pageWidth = width, pageHeight = height)
                270 -> PdfSpaceTransform(0f, -1f, -1f, 0f, x1, y1, pageWidth = height, pageHeight = width)
                else -> PdfSpaceTransform(1f, 0f, 0f, -1f, x0, y1, pageWidth = width, pageHeight = height)
            }
        }

        /**
         * Reduces /Rotate to 0, 90, 180 or 270. Values that are not multiples of 90 are truncated to
         * whole quarter turns, which is how PDFium (behind PdfRenderer) reads them — and PdfRenderer
         * defined the page space the annotations were drawn in.
         */
        fun normalizeRotation(rotation: Int): Int = (((rotation / 90) % 4 + 4) % 4) * 90
    }
}
