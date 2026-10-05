package com.studynotes.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.studynotes.app.editor.canvas.AnnotationPainter
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.pdf.PdfDocumentHandle
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Pixels per point for raster-fallback pages: 2x (144 dpi); see [rasterScale] for huge pages. */
internal const val RASTER_SCALE = 2f

/** Upper bound on one rasterised page (12 MP = 48 MB as ARGB_8888); only poster-sized pages hit it. */
internal const val MAX_RASTER_PIXELS = 12_000_000f

/** JPEG quality of raster-fallback pages: text stays crisp at 2x while a page is a few hundred KB. */
internal const val RASTER_JPEG_QUALITY = 0.9f

/** Scale (px per pt) used to rasterise a page of [widthPt] x [heightPt]. */
internal fun rasterScale(widthPt: Float, heightPt: Float): Float {
    val area = widthPt * heightPt
    if (!(area > 0f)) return RASTER_SCALE
    return min(RASTER_SCALE, sqrt(MAX_RASTER_PIXELS / area))
}

/**
 * Fallback export for PDFs PdfBox cannot process: every page is rendered with PdfRenderer, the
 * annotations are painted on top with [AnnotationPainter] (highlighter MULTIPLY blends with the page
 * pixels), and each bitmap becomes a JPEG image filling one page of a new PDF that keeps the
 * original page size in points. Text is no longer selectable, but the marks always come out right.
 *
 * The output is built with PdfBox — it is only the source that PdfBox failed to read — in a fresh
 * document whose streams live in a scratch file in [write]'s tempDir. Each page's bitmap is
 * compressed and freed before the next page is rendered, so peak memory is one page however long
 * the document is. (android.graphics.pdf.PdfDocument would keep every page's pixels alive until
 * writeTo, which is gigabytes for a long document.)
 */
internal object RasterPdfWriter {

    suspend fun write(
        source: File,
        annotations: DocumentAnnotations,
        out: File,
        includeNotes: Boolean,
        tempDir: File,
    ) {
        PdfDocumentHandle.open(source).use { handle ->
            val painter = AnnotationPainter()
            val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(tempDir)
            PDDocument(memory).use { doc ->
                for (index in 0 until handle.pageCount) {
                    currentCoroutineContext().ensureActive()
                    val size = handle.pageSizes[index]
                    val widthPt = size.width.coerceAtLeast(1f)
                    val heightPt = size.height.coerceAtLeast(1f)
                    val image = renderPageImage(doc, handle, painter, annotations, index, widthPt, heightPt, includeNotes)
                    val pdPage = PDPage(PDRectangle(widthPt, heightPt))
                    doc.addPage(pdPage)
                    PDPageContentStream(doc, pdPage).use { cs ->
                        // Stretch the image over the whole page (its height was rounded to whole pixels).
                        cs.drawImage(image, 0f, 0f, widthPt, heightPt)
                    }
                }
                currentCoroutineContext().ensureActive()
                doc.save(out)
            }
        }
    }

    /** Renders page [index] with its annotations and returns it as a JPEG image of [doc]. */
    private suspend fun renderPageImage(
        doc: PDDocument,
        handle: PdfDocumentHandle,
        painter: AnnotationPainter,
        annotations: DocumentAnnotations,
        index: Int,
        widthPt: Float,
        heightPt: Float,
        includeNotes: Boolean,
    ): PDImageXObject {
        val scale = rasterScale(widthPt, heightPt)
        val bitmap = mutableCopyOf(handle.renderPage(index, (widthPt * scale).roundToInt().coerceAtLeast(1)))
        try {
            val page = annotations.page(index)
            if (!page.isEmpty) {
                val canvas = Canvas(bitmap)
                // Per-axis scale, so the marks line up exactly with the stretched page image.
                canvas.scale(bitmap.width / widthPt, bitmap.height / heightPt)
                painter.drawPage(canvas, page, scale, drawNotes = includeNotes)
            }
            // The page was rendered onto white, so every pixel is opaque. Saying so stops JPEGFactory
            // from building a soft mask out of the (constant) alpha channel on the Java heap.
            bitmap.setHasAlpha(false)
            // The JPEG bytes are copied into the document's scratch file, so the pixels can go now.
            return JPEGFactory.createFromImage(doc, bitmap, RASTER_JPEG_QUALITY)
        } finally {
            bitmap.recycle()
        }
    }

    /** Canvas needs a mutable bitmap; the handle's renders are, but don't rely on it. */
    private fun mutableCopyOf(bitmap: Bitmap): Bitmap {
        if (bitmap.isMutable) return bitmap
        val copy: Bitmap? = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        bitmap.recycle()
        return copy ?: throw IllegalStateException("Could not copy the page bitmap")
    }
}
