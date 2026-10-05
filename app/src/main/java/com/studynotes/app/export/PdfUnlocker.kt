package com.studynotes.app.export

import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Makes an unencrypted working copy of a password-protected PDF so the viewer (PdfRenderer cannot
 * take a password before API 35) and the exporter can use it. The original file is untouched.
 *
 * CONTRACT (frozen):
 * - Loads [source] with PdfBox using [password] (temp-file memory), calls
 *   setAllSecurityToBeRemoved(true), saves to a temp file next to [out], renames to [out].
 * - Returns false if the password is wrong. Throws ExportException for any other failure.
 * - Main-safe (runs on Dispatchers.IO).
 */
object PdfUnlocker {

    private const val FAILED_MESSAGE = "Couldn't unlock this PDF."

    /** Writes an unencrypted copy of [source] to [out]; false if [password] is wrong. */
    suspend fun unlock(source: File, password: String, out: File): Boolean = withContext(Dispatchers.IO) {
        val part = ExportFiles.partFileFor(out)
        try {
            val dir = part.parentFile
            if (dir == null || (!dir.isDirectory && !dir.mkdirs())) throw IOException("Cannot create ${part.parent}")
            // Scratch files go to java.io.tmpdir, which Android points at the app's cache directory
            // (where stale PDFBox*.tmp files are cleaned up at start-up).
            PDDocument.load(source, password, MemoryUsageSetting.setupTempFileOnly()).use { doc ->
                doc.isAllSecurityToBeRemoved = true
                doc.save(part)
            }
            ExportFiles.moveIntoPlace(part, out)
            true
        } catch (e: InvalidPasswordException) {
            false
        } catch (e: Exception) {
            throw ExportException(FAILED_MESSAGE, e)
        } catch (e: OutOfMemoryError) {
            // A huge document; the heap is released with the closed PDDocument, so report and go on.
            throw ExportException(FAILED_MESSAGE, e)
        } finally {
            part.delete()
        }
    }
}
