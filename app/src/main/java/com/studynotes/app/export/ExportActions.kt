package com.studynotes.app.export

import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.studynotes.app.AppContainer
import com.studynotes.app.MainActivity
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.data.SidecarLoad
import com.studynotes.app.data.UnlockedCopies
import com.studynotes.app.docx.DocxConversionException
import com.studynotes.app.model.DocType
import com.studynotes.app.model.DocumentAnnotations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Shared "export / share annotated PDF" plumbing used by both the library and the editor.
 *
 * CONTRACT (frozen).
 */
object ExportActions {

    private const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    private const val CHOOSER_TITLE = "Share"
    private const val UNSUPPORTED_MESSAGE = "Only PDF and Word documents can be exported."
    private const val CONVERSION_MESSAGE = "Couldn't prepare this Word document for export."
    private const val COPY_MESSAGE = "Couldn't save the copy there. Try another location."
    private const val UNREADABLE_ANNOTATIONS_MESSAGE =
        "Couldn't read the saved highlights and notes for this document, so it wasn't exported."

    /**
     * Exports library document [docFile] (PDF or DOCX) with its annotations burned in, into
     * cacheDir/exports/<document key>/"<title> (annotated).pdf". Uses [annotations] if given (the
     * editor passes its live snapshot), otherwise loads the saved sidecar. For DOCX the PDF
     * rendition is the source.
     * Throws ExportException with a user-presentable message on failure. Main-safe.
     */
    suspend fun exportDocument(
        container: AppContainer,
        docFile: File,
        annotations: DocumentAnnotations? = null,
    ): ExportResult {
        val paths = container.libraryStorage.paths.value
        val type = DocType.fromFileName(docFile.name) ?: throw ExportException(UNSUPPORTED_MESSAGE)
        // Before the (possibly slow) DOCX conversion, which is wasted if the marks can't be read.
        val marks = annotations ?: savedAnnotations(container, paths, docFile)
        val source = when (type) {
            DocType.PDF -> withContext(Dispatchers.IO) { pdfSource(paths, docFile) }
            DocType.DOCX -> docxRendition(container, paths, docFile)
        }
        val exporter = container.exporter
        // A folder per document, so two documents with the same title never share an export file.
        // Without marks it's just the document as a PDF, so it isn't called "(annotated)".
        val target = ExportFiles.exportTargetFor(exporter.exportsDir, docFile.name, annotated = !marks.isEmpty)
        withContext(Dispatchers.IO) {
            ExportFiles.pruneExports(exporter.exportsDir, keep = target, now = System.currentTimeMillis())
        }
        return exporter.export(source, marks, target)
    }

    /**
     * The saved annotations of [docFile]; none if it was never annotated. A sidecar that exists but
     * can't be read fails the export: otherwise the user would share an "(annotated)" PDF without
     * any of their marks and never know.
     */
    private suspend fun savedAnnotations(container: AppContainer, paths: LibraryPaths, docFile: File): DocumentAnnotations =
        when (val saved = container.annotationStore.loadResult(paths, docFile.name)) {
            is SidecarLoad.Loaded -> saved.file.annotations
            SidecarLoad.Missing -> DocumentAnnotations.EMPTY
            is SidecarLoad.Unreadable -> throw ExportException(UNREADABLE_ANNOTATIONS_MESSAGE)
        }

    /**
     * A password-protected PDF is annotated on its unlocked working copy (made by [PdfUnlocker]),
     * so export from that copy while it is current — the same rule ([UnlockedCopies]) the editor
     * uses to open it. A stale copy means the original was replaced; export the original then.
     */
    private fun pdfSource(paths: LibraryPaths, docFile: File): File =
        UnlockedCopies.currentCopy(paths, docFile) ?: docFile

    private suspend fun docxRendition(container: AppContainer, paths: LibraryPaths, docFile: File): File =
        try {
            container.renditions.ensureRendition(paths, docFile)
        } catch (e: DocxConversionException) {
            throw ExportException(e.message ?: CONVERSION_MESSAGE, e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ExportException(CONVERSION_MESSAGE, e)
        }

    /**
     * A chooser Intent sharing [file] as application/pdf through the app's FileProvider.
     *
     * Also shares library originals (a .docx is sent with the Word MIME type). [file] must lie under
     * a FileProvider root (cache/exports or the library folder); otherwise FileProvider throws
     * IllegalArgumentException. The read grant travels with the ClipData, so it reaches both the
     * chooser's preview and the chosen app.
     */
    fun shareIntent(context: Context, file: File, title: String): Intent {
        val displayName = file.name
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + FILE_PROVIDER_SUFFIX,
            file,
            displayName,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = (DocType.fromFileName(displayName) ?: DocType.PDF).mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TITLE, title)
            clipData = ClipData.newRawUri(displayName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, CHOOSER_TITLE).apply {
            // StudyNotes itself accepts shared PDFs, but importing its own export is refused (and
            // pointless), so it is not offered as a target.
            putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
        }
    }

    /**
     * Copies [file] to a destination picked with ACTION_CREATE_DOCUMENT (may be Drive/OneDrive).
     * Main-safe. Throws [ExportException] with a user-presentable message on failure.
     */
    suspend fun copyToUri(context: Context, file: File, destination: Uri): Unit = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var writing = false
        try {
            // The source is opened first, so a missing export fails before the destination is touched.
            file.inputStream().use { input ->
                openForWriting(resolver, destination).use { out ->
                    // From here on the destination's old content is truncated ("wt") or being overwritten.
                    writing = true
                    val written = input.copyTo(out)
                    out.flush()
                    truncateTo(out, written)
                }
            }
        } catch (e: Exception) {
            // IO, SecurityException (grant revoked) and provider-specific runtime exceptions alike.
            // A half-written copy is a broken PDF (Drive would sync it on to the user's PC): remove it.
            if (writing) ExportDestinations.discardPartial(resolver, destination)
            throw ExportException(COPY_MESSAGE, e)
        }
    }

    /** "w" works with most providers; a few only accept "wt", whose failure then explains why. */
    private fun openForWriting(resolver: ContentResolver, uri: Uri): OutputStream {
        val stream = try {
            resolver.openOutputStream(uri, "w")
        } catch (e: Exception) {
            null
        }
        return stream ?: resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write to $uri")
    }

    /**
     * Mode "w" does not truncate on some providers (FUSE-backed shared storage on Android 10+), so
     * replacing a longer existing file would leave a stale tail. Truncate explicitly when the stream
     * is backed by a real file; pipes (most cloud providers) reject it and need nothing.
     */
    private fun truncateTo(out: OutputStream, length: Long) {
        if (out !is FileOutputStream) return
        try {
            out.channel.truncate(length)
        } catch (e: IOException) {
            // Not a seekable file (pipe or socket): the provider receives a fresh stream anyway.
        }
    }
}
