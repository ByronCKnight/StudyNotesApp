package com.studynotes.app.library

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import com.studynotes.app.data.AnnotationStore
import com.studynotes.app.data.AppSettings
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.docx.DocxRenditionManager
import com.studynotes.app.library.data.AnnotationCountCache
import com.studynotes.app.library.data.CloudProviders
import com.studynotes.app.library.data.DocumentMetadata
import com.studynotes.app.library.data.FileOps
import com.studynotes.app.library.data.ImportException
import com.studynotes.app.library.data.ImportProblem
import com.studynotes.app.library.data.LibraryFileNames
import com.studynotes.app.library.data.LibraryImporter
import com.studynotes.app.library.data.ListedDocument
import com.studynotes.app.library.data.OrphanSidecars
import com.studynotes.app.library.data.StoredDocument
import com.studynotes.app.library.data.ThumbnailDiskCache
import com.studynotes.app.model.DocType
import com.studynotes.app.model.LibraryDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class ImportResult(
    /** Files now in the library, in the order of the input URIs. */
    val imported: List<File>,
    /** Display names (or URIs) that could not be imported, with a short reason. */
    val failures: List<String>,
    /** The part of [imported] that was already in the library (identical content); nothing was copied. */
    val alreadyInLibrary: List<File> = emptyList(),
)

/**
 * The documents in the library folder, plus import/rename/delete.
 *
 * CONTRACT (frozen):
 * - [documents] lists *.pdf and *.docx directly inside LibraryStorage.paths.value.root (no
 *   recursion, hidden files and the .studynotes folder ignored), with lastOpened from AppSettings and
 *   annotationCount from the sidecar (strokes+notes; parsing may be cached by sidecar lastModified).
 *   Order is unspecified; the UI sorts.
 * - [importUris] copies content:// or file:// URIs into the library (never the app's own private
 *   files: file:// paths inside its sandbox and its own providers' URIs are reported as failures,
 *   so other apps can't use it to copy them into shared storage). Handles: display name via
 *   OpenableColumns, MIME-based extension when the name lacks one, Google-Docs-style virtual files
 *   (FLAG_VIRTUAL_DOCUMENT → openTypedAssetFileDescriptor as PDF, else DOCX), name clashes
 *   ("Name (2).pdf" — but if a byte-identical file already exists, reuse it instead of duplicating),
 *   unsupported types (reported as failures), copy to a temp file then rename. Refreshes [documents].
 * - [rename]/[delete] keep the sidecar, DOCX rendition, recents and thumbnails consistent.
 * - All functions are main-safe (do IO on Dispatchers.IO).
 *
 * Imported files get their extension from their content (magic bytes), not from the name or MIME
 * type, so a mislabelled file still lands as the right type and anything else is reported.
 */
class LibraryRepository(
    context: Context,
    private val storage: LibraryStorage,
    private val annotationStore: AnnotationStore,
    private val settings: AppSettings,
    private val renditions: DocxRenditionManager,
) {
    private val appContext: Context = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver
    private val thumbsDir = File(appContext.cacheDir, ThumbnailDiskCache.DIR_NAME)

    private val _documents = MutableStateFlow<List<LibraryDocument>>(emptyList())
    val documents: StateFlow<List<LibraryDocument>> = _documents.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)

    /**
     * False until the library folder has been listed for the first time, so the UI can show a
     * neutral loading state instead of flashing "No documents yet" at startup.
     */
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val counts = AnnotationCountCache()

    /** Guarded by [refreshMutex]. */
    private val orphans = OrphanSidecars()
    private val refreshMutex = Mutex()

    /** One import batch at a time: keeps temp file names and "Name (2)" choices race-free. */
    private val importMutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Lists the folder at startup and again whenever the library moves (e.g. after migration).
        scope.launch {
            storage.paths.collect {
                try {
                    refresh()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Listing the library failed", e)
                }
            }
        }
    }

    suspend fun refresh(): Unit = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val (paths, entries) = storage.withLibraryLock { paths ->
                val entries = listEntries(paths.root)
                reattachOrphanedData(paths, entries)
                paths to entries
            }
            // Counting decodes sidecars, which can take a while: done outside the library lock.
            val docs = entries.map { entry ->
                val sidecar = paths.sidecarFile(entry.file.name)
                LibraryDocument(
                    file = entry.file,
                    type = entry.type,
                    sizeBytes = entry.size,
                    lastModified = entry.lastModified,
                    lastOpened = settings.lastOpened(entry.file.name),
                    annotationCount = counts.count(sidecar, DocumentMetadata.backupOf(sidecar)),
                )
            }
            counts.retainOnly(docs.mapTo(HashSet()) { paths.sidecarFile(it.fileName).path })
            _documents.value = docs
            _isLoaded.value = true
        }
    }

    suspend fun importUris(uris: List<Uri>): ImportResult = withContext(Dispatchers.IO) {
        val imported = ArrayList<File>()
        val failures = ArrayList<String>()
        val alreadyInLibrary = ArrayList<File>()
        importMutex.withLock {
            val created = ArrayList<File>()
            try {
                for (uri in uris.distinct()) {
                    if (isAppPrivate(uri)) {
                        Log.w(TAG, "Refused to import one of the app's own private files")
                        failures += LibraryImporter.failureText(displayNameOf(uri), ImportProblem.NO_PERMISSION, remote = false)
                        continue
                    }
                    val source = describe(uri)
                    try {
                        val stored = importOne(uri, source)
                        imported += stored.file
                        if (stored.reused) alreadyInLibrary += stored.file else created += stored.file
                        CloudProviders.canonicalAuthority(uri.authority)?.let { settings.setLastPickerUri(it, uri.toString()) }
                    } catch (e: ImportException) {
                        Log.w(TAG, "Import failed: ${e.problem}", e)
                        failures += failureText(uri, source, e.problem)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Import failed unexpectedly", e)
                        failures += failureText(uri, source, ImportProblem.UNEXPECTED)
                    }
                }
            } finally {
                // Files copied before a cancellation are real library files too.
                storage.notifyMediaStore(created)
            }
        }
        refresh()
        ImportResult(imported, failures, alreadyInLibrary)
    }

    /**
     * Renames to [newTitle] (no extension). Throws IllegalArgumentException if invalid or taken.
     * Throws IOException if the file can't be renamed (it then keeps its old name and annotations).
     */
    suspend fun rename(doc: LibraryDocument, newTitle: String): File = withContext(Dispatchers.IO) {
        // A typed name is rejected rather than silently changed ("1/2" must not become "12").
        val illegal = LibraryFileNames.illegalCharsIn(newTitle)
        if (illegal.isNotEmpty()) {
            throw IllegalArgumentException("Names can't contain ${illegal.toList().joinToString(" ")}")
        }
        val title = LibraryFileNames.sanitizeTitle(newTitle) ?: throw IllegalArgumentException("The name can't be empty")
        val renamed = storage.withLibraryLock { paths ->
            val source = locate(doc, paths)
            val extension = source.name.substringAfterLast('.')
            val newName = "$title.$extension"
            if (newName == source.name) return@withLibraryLock source
            val newKey = LibraryFileNames.nameKey(newName)
            val clash = paths.root.list()?.any { it != source.name && LibraryFileNames.nameKey(it) == newKey } ?: false
            if (clash) throw IllegalArgumentException("A document with that name already exists")
            val target = File(paths.root, newName)
            if (!renameFile(source, target)) throw IOException("Couldn't rename the document")
            // Once the file has its new name, its data must follow even if the caller goes away.
            withContext(NonCancellable) { moveDocumentData(paths, source, target) }
            target
        }
        refresh()
        renamed
    }

    /** Deletes the document with its annotations, rendition, recents entry and thumbnails. */
    suspend fun delete(doc: LibraryDocument): Unit = withContext(Dispatchers.IO) {
        storage.withLibraryLock { paths ->
            checkInLibrary(doc, paths)
            val file = File(paths.root, doc.fileName)
            if (file.exists() && !file.delete()) throw IOException("Couldn't delete the document")
            withContext(NonCancellable) {
                bestEffort("delete the annotations") { annotationStore.delete(paths, doc.fileName) }
                bestEffort("delete the rendition") { renditions.delete(paths, doc.fileName) }
                settings.removeRecent(doc.fileName)
                ThumbnailDiskCache.deleteFor(thumbsDir, doc.fileName)
                storage.notifyMediaStore(listOf(file))
            }
        }
        refresh()
    }

    // ---- Listing ----

    private class Entry(val file: File, val type: DocType, val size: Long, val lastModified: Long)

    /** Lists the documents in [root] and clears temp files abandoned by an interrupted import/move. */
    private fun listEntries(root: File): List<Entry> {
        val now = System.currentTimeMillis()
        return root.listFiles().orEmpty().mapNotNull { file ->
            val name = file.name
            val type = DocType.fromFileName(name)
            when {
                type != null && LibraryFileNames.isLibraryDocumentName(name) ->
                    if (file.isFile) Entry(file, type, file.length(), file.lastModified()) else null
                isTempName(name) && now - file.lastModified() > STALE_TEMP_MILLIS -> {
                    file.delete()
                    null
                }
                else -> null
            }
        }
    }

    private fun isTempName(name: String) = name.startsWith(".") &&
        (name.endsWith(LibraryFileNames.PART_SUFFIX) || name.endsWith(FileOps.COPY_SUFFIX))

    /**
     * Gives a document renamed outside the app (Files app, a PC) its annotations back: its old
     * name's sidecar, rendition / unlocked copy and recents entry move to the new name when the
     * match is unambiguous (see [OrphanSidecars]). Must hold the library lock.
     */
    private suspend fun reattachOrphanedData(paths: LibraryPaths, entries: List<Entry>) {
        val listed = entries.map { ListedDocument(it.file.name, it.type, it.size, it.lastModified) }
        val matches = try {
            orphans.reattachments(paths, listed)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't look for annotations of renamed documents", e)
            return
        }
        for ((oldName, newName) in matches) {
            // A document open in the editor (or still saving) keeps what it has: the editor loaded
            // its sidecar already and would save over anything moved in now.
            if (annotationStore.hasPendingSave(paths, newName) || annotationStore.hasPendingSave(paths, oldName)) continue
            // Once the sidecar has moved, the rest must follow even if the caller goes away.
            withContext(NonCancellable) {
                try {
                    annotationStore.rename(paths, oldName, newName)
                } catch (e: Exception) {
                    // rename removes an old file only once it is copied, so nothing is lost.
                    Log.w(TAG, "Couldn't re-attach annotations to a renamed document", e)
                    return@withContext
                }
                // Moving a missing rendition would delete the document's own (an unlocked copy, say).
                if (paths.renditionFile(oldName).exists() || paths.renditionMetaFile(oldName).exists()) {
                    bestEffort("move the rendition") { renditions.rename(paths, oldName, newName) }
                }
                moveRecent(oldName, newName)
                ThumbnailDiskCache.deleteFor(thumbsDir, oldName)
            }
        }
    }

    /** Moves [oldName]'s "last opened" time to [newName], keeping the more recent of the two. */
    private fun moveRecent(oldName: String, newName: String) {
        val old = settings.lastOpened(oldName) ?: return
        val current = settings.lastOpened(newName)
        if (current == null || old > current) settings.renameRecent(oldName, newName) else settings.removeRecent(oldName)
    }

    // ---- Import ----

    /** What we know about an incoming URI before reading it. */
    private class IncomingSource(
        /** Provider's display name (with the export extension appended for virtual documents). */
        val displayName: String,
        /** For virtual documents: the format to export as; null = read the raw stream. */
        val exportType: DocType?,
        /** A virtual document whose provider offers neither PDF nor DOCX. */
        val unconvertible: Boolean,
        /** False when [displayName] is only a placeholder: messages then say "a file" instead. */
        val nameKnown: Boolean = true,
    )

    private fun describe(uri: Uri): IncomingSource {
        var name: String? = null
        var flags = 0
        var mime: String? = null
        if (uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true)) {
            name = uri.path?.let { File(it).name }
        } else {
            val isDocument = try {
                DocumentsContract.isDocumentUri(appContext, uri)
            } catch (e: RuntimeException) {
                false
            }
            val projection = if (isDocument) {
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_FLAGS,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                )
            } else {
                arrayOf(OpenableColumns.DISPLAY_NAME)
            }
            try {
                resolver.query(uri, projection, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        name = c.stringOrNull(OpenableColumns.DISPLAY_NAME)
                        flags = c.intOrZero(DocumentsContract.Document.COLUMN_FLAGS)
                        mime = c.stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    }
                }
            } catch (e: Exception) {
                // Providers throw all sorts of things for metadata queries; the name is a nicety.
                Log.w(TAG, "Couldn't query import metadata", e)
            }
        }
        val type = mime ?: try {
            resolver.getType(uri)
        } catch (e: Exception) {
            null
        }
        val knownName = name?.takeIf { it.isNotBlank() } ?: fileNameInUri(uri)
        val displayName = knownName ?: LibraryFileNames.DEFAULT_TITLE

        val flaggedVirtual = flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0
        val googleFormat = type?.startsWith(CloudProviders.GOOGLE_APPS_MIME_PREFIX) == true
        val exportType = if (flaggedVirtual || googleFormat) {
            val streamTypes = try {
                resolver.getStreamTypes(uri, "*/*")
            } catch (e: Exception) {
                null
            }
            CloudProviders.preferredExportType(streamTypes)
        } else {
            null
        }
        return IncomingSource(
            displayName = if (exportType != null) withExtension(displayName, exportType) else displayName,
            exportType = exportType,
            unconvertible = flaggedVirtual && exportType == null,
            nameKnown = knownName != null,
        )
    }

    /**
     * The file name at the end of [uri] ("primary:Download/Notes.pdf" -> "Notes.pdf"), if it looks
     * like one: providers often end URIs with a bare row id ("…/169"), which means nothing to users.
     */
    private fun fileNameInUri(uri: Uri): String? =
        uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':')
            ?.takeIf { it.isNotBlank() && '.' in it.trim('.') }

    private suspend fun importOne(uri: Uri, source: IncomingSource): StoredDocument {
        if (source.unconvertible) throw ImportException(ImportProblem.NOT_CONVERTIBLE)
        val root = storage.withLibraryLock { paths -> paths.root.takeIf { paths.ensureDirs() } }
            ?: throw ImportException(ImportProblem.LIBRARY_UNAVAILABLE)
        val part = File(root, LibraryFileNames.partFileName(source.displayName))
        try {
            // The download (possibly slow, from the network) runs outside the library lock.
            val job = currentCoroutineContext().job
            openSource(uri, source).use { input -> LibraryImporter.download(input, part) { job.ensureActive() } }
            return storage.withLibraryLock { paths ->
                // The library may have moved to the shared folder while we were downloading.
                val located = if (paths.root == root) part else relocate(part, paths)
                val stored = LibraryImporter.finalize(located, paths.root, source.displayName) { name ->
                    DocumentMetadata.exists(paths, name)
                }
                // A recents entry left by a document deleted outside the app is not this one's.
                if (!stored.reused) settings.removeRecent(stored.file.name)
                stored
            }
        } finally {
            part.delete()
        }
    }

    private fun openSource(uri: Uri, source: IncomingSource): InputStream {
        val stream = try {
            val exportType = source.exportType
            if (exportType != null) {
                resolver.openTypedAssetFileDescriptor(uri, exportType.mimeType, null)?.createInputStream()
            } else {
                resolver.openInputStream(uri)
            }
        } catch (e: SecurityException) {
            throw ImportException(ImportProblem.NO_PERMISSION, e)
        } catch (e: IOException) {
            throw ImportException(ImportProblem.READ_FAILED, e)
        } catch (e: RuntimeException) {
            throw ImportException(ImportProblem.READ_FAILED, e)
        }
        return stream ?: throw ImportException(ImportProblem.READ_FAILED)
    }

    private fun relocate(part: File, paths: LibraryPaths): File {
        if (!paths.ensureDirs()) throw ImportException(ImportProblem.LIBRARY_UNAVAILABLE)
        val target = File(paths.root, part.name)
        target.delete() // a stale temp file from an interrupted import
        if (!FileOps.move(part, target)) throw ImportException(ImportProblem.WRITE_FAILED)
        return target
    }

    /**
     * True for a URI that would make the app copy one of its own private files into the library
     * (shared storage, when public): a file:// path inside the app's sandbox, or a content:// URI
     * of one of its own providers, which it can always read. Without this check any app could use
     * "Share to StudyNotes" to pull out, say, an exported PDF from the app's cache. Blocking IO.
     */
    private fun isAppPrivate(uri: Uri): Boolean = when {
        uri.scheme.equals(ContentResolver.SCHEME_FILE, ignoreCase = true) ->
            uri.path?.let { isInsideSandbox(File(it)) } ?: true
        uri.scheme.equals(ContentResolver.SCHEME_CONTENT, ignoreCase = true) -> isOwnProvider(uri.authority)
        else -> false
    }

    private fun isInsideSandbox(file: File): Boolean {
        // Canonical paths, so "/data/data/<pkg>" vs "/data/user/0/<pkg>" and "../" tricks can't slip through.
        val target = try {
            file.canonicalFile
        } catch (e: IOException) {
            return true
        }
        val externalAppDirs = appContext.getExternalFilesDirs(null).mapNotNull { it?.parentFile } +
            listOfNotNull(appContext.externalCacheDir?.parentFile)
        val sandbox = listOfNotNull(appContext.dataDir, appContext.filesDir, appContext.cacheDir, appContext.noBackupFilesDir) +
            externalAppDirs
        return sandbox.any { dir ->
            val root = try {
                dir.canonicalFile
            } catch (e: IOException) {
                dir.absoluteFile
            }
            target.startsWith(root)
        }
    }

    private fun isOwnProvider(authority: String?): Boolean {
        if (authority == null) return false
        if (authority == appContext.packageName + FILE_PROVIDER_SUFFIX) return true
        return try {
            val pm = appContext.packageManager
            val provider = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.resolveContentProvider(authority, 0)
            }
            provider?.packageName == appContext.packageName
        } catch (e: RuntimeException) {
            false
        }
    }

    /** A name for a URI that is not queried (refused before its provider is contacted). */
    private fun displayNameOf(uri: Uri): String? = fileNameInUri(uri)

    private fun failureText(uri: Uri, source: IncomingSource, problem: ImportProblem): String =
        LibraryImporter.failureText(
            source.displayName.takeIf { source.nameKnown },
            problem,
            remote = !CloudProviders.isLocal(uri.scheme, uri.authority),
        )

    private fun withExtension(name: String, type: DocType): String =
        if (name.endsWith(".${type.extension}", ignoreCase = true)) name else "$name.${type.extension}"

    // ---- Rename / delete ----

    /** Fails if [doc] comes from a listing of a previous library location (before a migration). */
    private fun checkInLibrary(doc: LibraryDocument, paths: LibraryPaths) {
        if (doc.file.parentFile?.absolutePath != paths.root.absolutePath) {
            throw FileNotFoundException("This document was moved. Refresh the library and try again.")
        }
    }

    private fun locate(doc: LibraryDocument, paths: LibraryPaths): File {
        checkInLibrary(doc, paths)
        val file = File(paths.root, doc.fileName)
        if (!file.isFile) throw FileNotFoundException("This document is no longer in the library")
        return file
    }

    /**
     * Renames [source] to [target] (which must not be another document). Shared storage is
     * case-insensitive, so a change of case only goes through a temporary name.
     */
    private fun renameFile(source: File, target: File): Boolean {
        if (!isCaseOnlyChange(source.name, target.name)) return !target.exists() && source.renameTo(target)
        val temp = File(source.parentFile, ".rename-" + UUID.randomUUID())
        if (!source.renameTo(temp)) return false
        if (temp.renameTo(target)) return true
        temp.renameTo(source)
        return false
    }

    /**
     * Moves the sidecar, rendition, recents entry and thumbnails from [source]'s name to [target]'s.
     * If the sidecar can't follow, everything (including the document) is put back and IOException
     * is thrown, so a document never silently loses its annotations.
     */
    private suspend fun moveDocumentData(paths: LibraryPaths, source: File, target: File) {
        val oldName = source.name
        val newName = target.name
        val hops = if (isCaseOnlyChange(oldName, newName)) {
            // Same reasoning as renameFile: "a.pdf.json" -> "A.pdf.json" is a no-op on shared storage.
            val temp = ".rename-" + UUID.randomUUID() + "." + oldName.substringAfterLast('.')
            listOf(oldName to temp, temp to newName)
        } else {
            // newName isn't a document, so anything stored under it is an orphan from a file that
            // was deleted outside the app; it must not be inherited by this document.
            bestEffort("clear orphaned annotations") { annotationStore.delete(paths, newName) }
            bestEffort("clear an orphaned rendition") { renditions.delete(paths, newName) }
            listOf(oldName to newName)
        }
        var done = 0
        try {
            for ((from, to) in hops) {
                annotationStore.rename(paths, from, to)
                bestEffort("move the rendition") { renditions.rename(paths, from, to) }
                done++
            }
        } catch (e: Exception) {
            for ((from, to) in hops.take(done).asReversed()) {
                bestEffort("restore the annotations") { annotationStore.rename(paths, to, from) }
                bestEffort("restore the rendition") { renditions.rename(paths, to, from) }
            }
            renameFile(target, source)
            throw IOException("Couldn't rename the document", e)
        }
        settings.renameRecent(oldName, newName)
        ThumbnailDiskCache.deleteFor(thumbsDir, oldName)
        storage.notifyMediaStore(listOf(source, target))
    }

    private fun isCaseOnlyChange(a: String, b: String) =
        a != b && LibraryFileNames.nameKey(a) == LibraryFileNames.nameKey(b)

    /** Runs a secondary step whose failure must not fail the user's action. */
    private inline fun bestEffort(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't $what", e)
        }
    }

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getString(index) else null
    }

    private fun Cursor.intOrZero(column: String): Int {
        val index = getColumnIndex(column)
        return if (index >= 0 && !isNull(index)) getInt(index) else 0
    }

    private companion object {
        const val TAG = "LibraryRepository"

        /** Temp files untouched for this long belong to an import or move that was interrupted. */
        const val STALE_TEMP_MILLIS = 60 * 60 * 1000L

        /** The app's FileProvider authority is `<applicationId>.fileprovider`. */
        const val FILE_PROVIDER_SUFFIX = ".fileprovider"
    }
}
