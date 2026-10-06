package com.studynotes.app.pdf

import kotlin.math.ceil
import kotlin.math.roundToInt

/** Size of a page in whole PDF points, as reported by PdfRenderer (crop box, rotation applied). */
internal data class PageDimensions(val width: Int, val height: Int)

/** Pixel size of a bitmap to allocate. */
internal data class PixelSize(val width: Int, val height: Int)

/**
 * Pure size/coordinate arithmetic for rendering, kept free of android.* types so it is unit-testable.
 */
internal object RenderGeometry {

    /**
     * Float noise tolerated when rounding a region up to whole pixels. Tiles are requested as
     * `tilePx / scale` page points, so `width * scale` comes back as e.g. 512.00006; a plain ceil
     * would make that 513 px and defeat the caller's 512 px bitmap pool.
     */
    private const val CEIL_TOLERANCE_PX = 0.01

    /** Bitmap size for a whole page rendered [widthPx] wide: height follows the aspect ratio, rounded, min 1. */
    fun pageBitmapSize(widthPx: Int, page: PageDimensions): PixelSize {
        val height = (widthPx.toDouble() * page.height / page.width).roundToInt()
        return PixelSize(widthPx, height.coerceAtLeast(1))
    }

    /**
     * Bitmap size for rendering the region ([left], [top], [right], [bottom]) of [page] (page points)
     * at [pxPerPt]: `ceil(width * pxPerPt) x ceil(height * pxPerPt)`, each at least 1 px.
     * Returns null when the region is empty, not finite, or does not overlap the page at all.
     */
    fun regionBitmapSize(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        pxPerPt: Float,
        page: PageDimensions,
    ): PixelSize? {
        if (!(left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite())) return null
        if (right <= left || bottom <= top) return null
        if (right <= 0f || bottom <= 0f || left >= page.width || top >= page.height) return null
        return PixelSize(ceilPx((right - left) * pxPerPt), ceilPx((bottom - top) * pxPerPt))
    }

    private fun ceilPx(value: Float): Int =
        ceil(value.toDouble() - CEIL_TOLERANCE_PX).toInt().coerceAtLeast(1)
}
