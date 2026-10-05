package com.studynotes.app.export

import android.content.Context
import android.util.Log
import com.studynotes.app.model.DocumentAnnotations
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/** A finished export: [file] is the annotated PDF. */
data class ExportResult(
    val file: File,
    /** True when PdfBox could not process the source and pages were rasterised instead. */
    val usedRasterFallback: Boolean,
)

/** Export, unlock or copy failed; the message is short and user-presentable. */
class ExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Produces a portable PDF with the user's highlights, pen ink and notes burned in, so they show up
 * in any PDF reader (Drive, OneDrive, Acrobat, a PC). The source file is never modified.
 *
 * CONTRACT (frozen):
 * - Primary path: PdfBox-Android. Ink is appended to each page's content stream as vector paths
 *   (highlighters with /BM /Multiply), mapped from page space (see model/Annotations.kt) to PDF
 *   user space honouring the crop box and /Rotate. Notes are drawn as images of the note card
 *   (rendered with AnnotationPainter) at AnnotationPainter.noteBounds, plus a PDF Text annotation
 *   (sticky-note comment) carrying the full note text.
 * - Fallback when PdfBox fails: rasterise each page (PdfDocumentHandle, ~2x) + AnnotationPainter
 *   into a new image-only PDF, one page in memory at a time (see RasterPdfWriter);
 *   [ExportResult.usedRasterFallback] = true.
 * - Writes to a temp file then renames to [outFile]. Runs on background dispatchers.
 * - Throws [ExportException] if both paths fail.
 */
class AnnotatedPdfExporter(context: Context) {

    private val appContext = context.applicationContext

    /** Exports run one at a time: each holds a whole document in PdfBox or PdfRenderer. */
    private val mutex = Mutex()

    /**
     * Where [ExportActions] puts exported PDFs (cacheDir/exports). The FileProvider's "exports"
     * root covers it, so files here can be shared.
     */
    val exportsDir: File get() = File(appContext.cacheDir, EXPORTS_DIR_NAME)

    /**
     * Writes [sourcePdf] with [annotations] burned in to [outFile] (replacing it). [includeNotes]
     * = false leaves out both the note cards and their comments. Main-safe and cancellable; a
     * cancelled export leaves no partial file behind. Throws [ExportException] with a
     * user-presentable message.
     */
    suspend fun export(
        sourcePdf: File,
        annotations: DocumentAnnotations,
        outFile: File,
        includeNotes: Boolean = true,
    ): ExportResult = withContext(Dispatchers.IO) {
        mutex.withLock { exportLocked(sourcePdf, annotations, outFile, includeNotes) }
    }

    private suspend fun exportLocked(
        source: File,
        annotations: DocumentAnnotations,
        outFile: File,
        includeNotes: Boolean,
    ): ExportResult {
        if (!source.isFile) throw ExportException(MISSING_MESSAGE)
        if (isSameFile(source, outFile)) throw ExportException(FAILED_MESSAGE)
        val dir = outFile.absoluteFile.parentFile
        if (dir == null || (!dir.isDirectory && !dir.mkdirs())) throw ExportException(FAILED_MESSAGE)

        val part = ExportFiles.partFileFor(outFile)
        try {
            val usedRasterFallback = writeAnnotatedPdf(source, annotations, part, includeNotes)
            try {
                ExportFiles.moveIntoPlace(part, outFile)
            } catch (e: IOException) {
                throw ExportException(FAILED_MESSAGE, e)
            }
            return ExportResult(outFile, usedRasterFallback)
        } finally {
            // No-op after a successful move; removes the partial file after a failure or cancellation.
            part.delete()
        }
    }

    /** Writes [part] with the vector path, falling back to rasterising. Returns true for the fallback. */
    private suspend fun writeAnnotatedPdf(
        source: File,
        annotations: DocumentAnnotations,
        part: File,
        includeNotes: Boolean,
    ): Boolean {
        // Anything PdfBox can't handle (malformed files, unsupported features, even running out of
        // memory on a huge document) is worth a raster attempt: PdfRenderer reads the source and
        // PdfBox only writes fresh image pages, one at a time, which needs far less memory.
        val vectorFailure: Throwable = try {
            VectorPdfWriter(appContext.cacheDir).write(source, annotations, part, includeNotes)
            return false
        } catch (e: InvalidPasswordException) {
            // PdfRenderer can't open it either; the editor's unlock flow makes a usable copy.
            throw ExportException(PASSWORD_MESSAGE, e)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            t
        }
        Log.w(TAG, "PdfBox export failed; rasterising pages instead", vectorFailure)
        part.delete()
        try {
            RasterPdfWriter.write(source, annotations, part, includeNotes, appContext.cacheDir)
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // ART may rethrow one pre-allocated OutOfMemoryError, and self-suppression would throw.
            if (t !== vectorFailure) t.addSuppressed(vectorFailure)
            Log.w(TAG, "Raster export failed", t)
            throw ExportException(FAILED_MESSAGE, t)
        }
    }

    /** Guards against ever replacing the user's original with the export. */
    private fun isSameFile(a: File, b: File): Boolean =
        runCatching { a.canonicalFile == b.canonicalFile }.getOrElse { a.absoluteFile == b.absoluteFile }

    private companion object {
        const val TAG = "AnnotatedPdfExporter"
        const val EXPORTS_DIR_NAME = "exports"
        const val FAILED_MESSAGE = "Couldn't export this document."
        const val MISSING_MESSAGE = "This document is no longer in your library."
        const val PASSWORD_MESSAGE =
            "This PDF is password-protected. Open it in StudyNotes and unlock it first."
    }
}
