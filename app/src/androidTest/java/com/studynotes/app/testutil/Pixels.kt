package com.studynotes.app.testutil

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rendered page plus its scale, addressed in page points (page space: origin top-left, y down).
 * Pixels are copied out once so lookups are cheap.
 */
class PagePixels(bitmap: Bitmap, val scale: Float, val widthPt: Float, val heightPt: Float) {
    val widthPx = bitmap.width
    val heightPx = bitmap.height
    private val pixels = IntArray(widthPx * heightPx).also { bitmap.getPixels(it, 0, widthPx, 0, 0, widthPx, heightPx) }

    /** ARGB of the pixel containing page point ([x], [y]), clamped to the bitmap. */
    fun at(x: Float, y: Float): Int {
        val px = (x * scale).toInt().coerceIn(0, widthPx - 1)
        val py = (y * scale).toInt().coerceIn(0, heightPx - 1)
        return pixels[py * widthPx + px]
    }

    /** Number of pixels inside page rect [r] whose luminance is below [threshold]. */
    fun darkCount(r: RectF, threshold: Int = 128): Int {
        var n = 0
        forEachPixel(r) { c -> if (luminance(c) < threshold) n++ }
        return n
    }

    /** Number of pixels inside page rect [r] that are not (near) white. */
    fun nonWhiteCount(r: RectF, minChannel: Int = 240): Int {
        var n = 0
        forEachPixel(r) { c -> if (minChannel(c) < minChannel) n++ }
        return n
    }

    /** Lowest luminance among the pixels within [radiusPt] (a square) of page point ([x], [y]). */
    fun minLuminanceAround(x: Float, y: Float, radiusPt: Float): Int {
        var m = 255
        forEachPixel(RectF(x - radiusPt, y - radiusPt, x + radiusPt, y + radiusPt)) { c -> m = min(m, luminance(c)) }
        return m
    }

    /** Highest chroma (max - min channel) among the pixels within [radiusPt] of ([x], [y]). */
    fun maxChromaAround(x: Float, y: Float, radiusPt: Float): Int {
        var m = 0
        forEachPixel(RectF(x - radiusPt, y - radiusPt, x + radiusPt, y + radiusPt)) { c -> m = max(m, chroma(c)) }
        return m
    }

    private inline fun forEachPixel(r: RectF, action: (Int) -> Unit) {
        val x0 = (r.left * scale).toInt().coerceIn(0, widthPx)
        val x1 = (r.right * scale).toInt().coerceIn(0, widthPx)
        val y0 = (r.top * scale).toInt().coerceIn(0, heightPx)
        val y1 = (r.bottom * scale).toInt().coerceIn(0, heightPx)
        for (y in y0 until y1) for (x in x0 until x1) action(pixels[y * widthPx + x])
    }

    companion object {
        fun luminance(c: Int): Int =
            (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)).roundToInt()

        fun minChannel(c: Int): Int = min(Color.red(c), min(Color.green(c), Color.blue(c)))

        fun chroma(c: Int): Int = max(Color.red(c), max(Color.green(c), Color.blue(c))) - minChannel(c)

        /** Largest per-channel difference between two colours. */
        fun channelDiff(a: Int, b: Int): Int = max(
            abs(Color.red(a) - Color.red(b)),
            max(abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b))),
        )

        fun hex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)
    }
}

/**
 * Opens a PDF with the plain platform android.graphics.pdf.PdfRenderer — deliberately not the code
 * under test — to look at what a third-party viewer would show.
 */
class PlatformPdf(file: File) : Closeable {
    private val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = PdfRenderer(descriptor)

    val pageCount: Int get() = renderer.pageCount

    /** Page size in points (crop box, rotation applied). */
    fun pageSize(index: Int): Pair<Int, Int> = renderer.openPage(index).use { it.width to it.height }

    /** Renders page [index] onto white at [scale] px per point. */
    fun render(index: Int, scale: Float): PagePixels = renderer.openPage(index).use { page ->
        val w = (page.width * scale).roundToInt().coerceAtLeast(1)
        val h = (page.height * scale).roundToInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, Matrix().apply { setScale(scale, scale) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            PagePixels(bitmap, scale, page.width.toFloat(), page.height.toFloat())
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        runCatching { renderer.close() }
        runCatching { descriptor.close() }
    }
}
