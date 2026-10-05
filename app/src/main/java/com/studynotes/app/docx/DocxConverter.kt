package com.studynotes.app.docx

import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile
import kotlin.coroutines.cancellation.CancellationException

/**
 * On-device DOCX → PDF conversion: [DocxParser] builds the document model, [DocxPdfRenderer] lays it
 * out into an [PdfDocument], and the result is written to a temp file next to the target and then
 * renamed, so a crash never leaves a half-written PDF behind.
 *
 * CPU and memory heavy: call from a background thread.
 */
object DocxConverter {
    /**
     * Version of the converter's output. Recorded in rendition bookkeeping; bump it when the layout
     * changes in a way worth knowing about (existing renditions are still reused, see
     * [DocxRenditionManager]).
     */
    const val VERSION = 3

    const val TOO_LARGE_MESSAGE = "This document is too large to convert on this device."
    const val SAVE_FAILED_MESSAGE = "Couldn't save the converted document. Check that there's free storage space."
    const val GENERIC_MESSAGE = "This Word document couldn't be converted."

    private const val TEMP_SUFFIX = ".tmp"
    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

    /**
     * Converts [docx] into a PDF at [outPdf] (replacing it). [checkpoint] is called regularly and may
     * throw a CancellationException to abandon the work. Every other failure, including running out
     * of memory, surfaces as a [DocxConversionException] with a user-presentable message.
     */
    fun convert(docx: File, outPdf: File, checkpoint: () -> Unit = {}) {
        try {
            val document = DocxParser.parse(docx, checkpoint)
            checkpoint()
            writePdf(docx, document, outPdf, checkpoint)
        } catch (e: DocxConversionException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            throw DocxConversionException(TOO_LARGE_MESSAGE, e)
        } catch (e: StackOverflowError) {
            throw DocxConversionException(TOO_LARGE_MESSAGE, e)
        } catch (e: Throwable) {
            throw DocxConversionException(GENERIC_MESSAGE, e)
        }
    }

    private fun writePdf(docx: File, document: DocxDocument, outPdf: File, checkpoint: () -> Unit) {
        val target = outPdf.absoluteFile
        val dir = target.parentFile ?: throw DocxConversionException(SAVE_FAILED_MESSAGE)
        if (!dir.isDirectory && !dir.mkdirs()) throw DocxConversionException(SAVE_FAILED_MESSAGE)
        val tmp = File(dir, target.name + TEMP_SUFFIX)
        try {
            ZipFile(docx).use { zip ->
                val parts = zip.asDocxParts()
                val pdf = PdfDocument()
                var primary: Throwable? = null
                try {
                    DocxPdfRenderer(
                        doc = document,
                        loadImage = { name -> parts.open(name)?.use { readLimited(it) } },
                        checkpoint = checkpoint,
                    ).render(pdf)
                    checkpoint()
                    save(pdf, tmp)
                } catch (t: Throwable) {
                    primary = t
                    throw t
                } finally {
                    // close() refuses (IllegalStateException) while a page is still open; that must
                    // never replace the real failure (cancellation, OOM, too long) on its way out.
                    try {
                        pdf.close()
                    } catch (e: IllegalStateException) {
                        primary?.addSuppressed(e) ?: throw e
                    }
                }
            }
            moveInto(tmp, target)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    private fun save(pdf: PdfDocument, file: File) {
        try {
            FileOutputStream(file).use { out ->
                pdf.writeTo(out)
                out.flush()
                runCatching { out.fd.sync() }
            }
        } catch (e: IOException) {
            throw DocxConversionException(SAVE_FAILED_MESSAGE, e)
        }
    }

    private fun moveInto(tmp: File, target: File) {
        if (tmp.renameTo(target)) return
        target.delete()
        if (tmp.renameTo(target)) return
        // Some FUSE-backed volumes refuse renames; fall back to copying.
        try {
            tmp.copyTo(target, overwrite = true)
        } catch (e: IOException) {
            target.delete()
            throw DocxConversionException(SAVE_FAILED_MESSAGE, e)
        }
    }

    /** Reads a picture, refusing absurdly large entries (zip bombs) instead of exhausting memory. */
    private fun readLimited(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > MAX_IMAGE_BYTES) return null
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
