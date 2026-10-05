package com.studynotes.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.studynotes.app.editor.canvas.AnnotationPainter
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Note
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.Stroke
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationText
import com.tom_roush.pdfbox.util.Matrix
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.util.Calendar
import kotlin.math.ceil

/**
 * The primary export path: loads the source with PdfBox and appends the annotations to each
 * annotated page's content stream as vector graphics, leaving every other page untouched.
 * Blocking; call on Dispatchers.IO. Each [write] creates its own per-document state.
 */
internal class VectorPdfWriter(private val tempDir: File) {

    /**
     * Writes [source] with [annotations] burned in to [out]. Throws
     * com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException for password-protected
     * sources, and whatever PdfBox throws for anything it can't handle.
     */
    suspend fun write(source: File, annotations: DocumentAnnotations, out: File, includeNotes: Boolean) {
        val memory = MemoryUsageSetting.setupTempFileOnly().setTempDir(tempDir)
        PDDocument.load(source, memory).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val pass = DocumentPass(doc, includeNotes)
            val pageCount = doc.numberOfPages
            for ((index, page) in annotations.pages.toSortedMap()) {
                currentCoroutineContext().ensureActive()
                // Annotations beyond the last page (e.g. the source shrank) have nowhere to go.
                if (index in 0 until pageCount) pass.annotate(doc.getPage(index), page)
            }
            // Blend modes, constant alpha and soft-masked images need PDF 1.4.
            if (pass.modified && doc.version < MIN_PDF_VERSION) doc.version = MIN_PDF_VERSION
            currentCoroutineContext().ensureActive()
            doc.save(out)
        }
    }

    /** State shared by all pages of one document: the highlighter graphics state and the note painter. */
    private class DocumentPass(private val doc: PDDocument, private val includeNotes: Boolean) {
        var modified = false
            private set

        private val painter by lazy(LazyThreadSafetyMode.NONE) { AnnotationPainter() }

        /** One /ExtGState for every highlighter in the document (MULTIPLY, constant alpha). */
        private val highlightState by lazy(LazyThreadSafetyMode.NONE) {
            PDExtendedGraphicsState().apply {
                blendMode = BlendMode.MULTIPLY
                strokingAlphaConstant = HighlightColor.ALPHA
                // Single-point highlights are filled dots, so the fill alpha must match.
                nonStrokingAlphaConstant = HighlightColor.ALPHA
            }
        }

        fun annotate(pdPage: PDPage, page: PageAnnotations) {
            val strokes = page.strokes.filter(StrokeGeometry::isDrawable)
            val notes = if (includeNotes) page.notes.mapNotNull(::place) else emptyList()
            if (strokes.isEmpty() && notes.isEmpty()) return

            val crop = pdPage.cropBox
            // The raw /Rotate, not pdPage.rotation (0 for non-multiples of 90): the editor's page
            // space comes from PDFium, which truncates e.g. 100 to a quarter turn.
            val transform = PdfSpaceTransform.displayToPdf(
                PdfPageInspection.rawRotation(pdPage.cosObject),
                crop.lowerLeftX, crop.lowerLeftY, crop.upperRightX, crop.upperRightY,
            )
            // Must be measured before the content stream below changes the page's /Contents.
            val unclosedSaves = PdfPageInspection.unclosedSaveDepth(pdPage)
            // APPEND + resetContext wraps the existing content in q/Q so its leftover graphics state
            // (CTM, colours, clipping) cannot leak into the annotations. That single Q only gets
            // back to the page's initial state if the old content's own q/Q are balanced, so close
            // whatever it left open first.
            PDPageContentStream(doc, pdPage, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                repeat(unclosedSaves) { cs.restoreGraphicsState() }
                with(transform) { cs.transform(Matrix(a, b, c, d, e, f)) }
                val highlighters = strokes.filter { it.tool == InkTool.HIGHLIGHTER }
                if (highlighters.isNotEmpty()) {
                    cs.saveGraphicsState()
                    cs.setGraphicsStateParameters(highlightState)
                    for (stroke in highlighters) drawStroke(cs, stroke, HighlightColor.compensatedRgb(stroke.color))
                    cs.restoreGraphicsState()
                }
                for (stroke in strokes) {
                    if (stroke.tool == InkTool.PEN) drawStroke(cs, stroke, rgbComponents(stroke.color))
                }
                for (placed in notes) drawNoteCard(cs, placed)
            }
            addComments(pdPage, notes, transform)
            modified = true
        }

        private fun drawStroke(cs: PDPageContentStream, stroke: Stroke, rgb: FloatArray) {
            val sink = ContentStreamPath(cs)
            if (StrokeGeometry.isDot(stroke)) {
                cs.setNonStrokingColor(rgb[0], rgb[1], rgb[2])
                StrokeGeometry.circle(stroke.points[0], stroke.points[1], stroke.width / 2f, sink)
                cs.fill()
            } else {
                cs.setStrokingColor(rgb[0], rgb[1], rgb[2])
                cs.setLineWidth(stroke.width)
                cs.setLineCapStyle(if (stroke.flatCaps) LINE_CAP_BUTT else LINE_CAP_ROUND)
                cs.setLineJoinStyle(LINE_JOIN_ROUND)
                StrokeGeometry.smoothPath(stroke.points, sink)
                cs.stroke()
            }
        }

        /** Pairs [note] with a copy of its card rectangle; null for corrupt (non-finite) positions. */
        private fun place(note: Note): PlacedNote? {
            val bounds = RectF(painter.noteBounds(note))
            val finite = bounds.left.isFinite() && bounds.top.isFinite() &&
                bounds.right.isFinite() && bounds.bottom.isFinite()
            return if (finite) PlacedNote(note, bounds) else null
        }

        /**
         * Draws the note card exactly as the app does by rendering it with [AnnotationPainter] into a
         * bitmap and placing that at the card rectangle. The image matrix maps the unit square's top
         * edge to the card's top: page space has y pointing down, so the height is negative and the
         * origin sits at the card's bottom-left.
         */
        private fun drawNoteCard(cs: PDPageContentStream, placed: PlacedNote) {
            val bounds = placed.bounds
            val widthPx = ceil(bounds.width() * NOTE_RENDER_SCALE).toInt().coerceAtLeast(1)
            val heightPx = ceil(bounds.height() * NOTE_RENDER_SCALE).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                canvas.scale(NOTE_RENDER_SCALE, NOTE_RENDER_SCALE)
                canvas.translate(-bounds.left, -bounds.top)
                painter.drawNote(canvas, placed.note, NOTE_RENDER_SCALE)
                val image = LosslessFactory.createFromImage(doc, bitmap)
                // Use the bitmap's rounded-up size so the card is not stretched by the rounding.
                val w = widthPx / NOTE_RENDER_SCALE
                val h = heightPx / NOTE_RENDER_SCALE
                cs.drawImage(image, Matrix(w, 0f, 0f, -h, bounds.left, bounds.top + h))
            } finally {
                bitmap.recycle()
            }
        }

        /**
         * Adds a sticky-note comment carrying each note's full text (the card image may be
         * ellipsized), so readers can open it in any PDF viewer's comments panel.
         */
        private fun addComments(pdPage: PDPage, notes: List<PlacedNote>, transform: PdfSpaceTransform) {
            val comments = notes.filter { it.note.text.isNotBlank() }.map { (note, card) ->
                val r = transform.commentIconRect(card.right, card.top, COMMENT_ICON_SIZE, COMMENT_ICON_GAP)
                PDAnnotationText().apply {
                    name = PDAnnotationText.NAME_COMMENT
                    contents = note.text
                    color = PDColor(rgbComponents(note.color), PDDeviceRGB.INSTANCE)
                    isPrinted = true
                    rectangle = PDRectangle(r[0], r[1], r[2] - r[0], r[3] - r[1])
                    annotationName = note.id
                    page = pdPage
                    if (note.createdAt > 0) creationDate = calendarAt(note.createdAt)
                    if (note.modifiedAt > 0) setModifiedDate(calendarAt(note.modifiedAt))
                    constructAppearances(doc)
                }
            }
            if (comments.isEmpty()) return
            // A fresh list (and so a fresh /Annots array) avoids mutating an array other pages share.
            val annotations = ArrayList<PDAnnotation>(pdPage.annotations)
            annotations.addAll(comments)
            pdPage.annotations = annotations
        }

        private fun calendarAt(millis: Long): Calendar = Calendar.getInstance().apply { timeInMillis = millis }
    }

    /** A note and its card rectangle in page space. */
    private data class PlacedNote(val note: Note, val bounds: RectF)

    /** Adapts [PathSink] to PDF path operators. */
    private class ContentStreamPath(private val cs: PDPageContentStream) : PathSink {
        override fun moveTo(x: Float, y: Float) = cs.moveTo(x, y)
        override fun lineTo(x: Float, y: Float) = cs.lineTo(x, y)
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) =
            cs.curveTo(x1, y1, x2, y2, x3, y3)
        override fun closePath() = cs.closePath()
    }

    private companion object {
        const val MIN_PDF_VERSION = 1.4f
        const val LINE_CAP_BUTT = 0
        const val LINE_CAP_ROUND = 1
        const val LINE_JOIN_ROUND = 1

        /** Note cards are rasterised at 3 px per point: sharp when printed or zoomed in. */
        const val NOTE_RENDER_SCALE = 3f

        /** Size of PdfBox's generated comment icon appearance (18 × 18 pt). */
        const val COMMENT_ICON_SIZE = 18f
        const val COMMENT_ICON_GAP = 2f
    }
}
