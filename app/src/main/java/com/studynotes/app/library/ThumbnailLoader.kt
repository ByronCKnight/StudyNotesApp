package com.studynotes.app.library

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Log
import android.util.LruCache
import android.util.SizeF
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.data.UnlockedCopies
import com.studynotes.app.docx.DocxRenditionManager
import com.studynotes.app.editor.canvas.AnnotationPainter
import com.studynotes.app.library.data.ThumbnailDiskCache
import com.studynotes.app.model.AnnotationJson
import com.studynotes.app.model.DocType
import com.studynotes.app.model.LibraryDocument
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.pdf.PdfDocumentHandle
import com.studynotes.app.pdf.PdfOpenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * First-page thumbnails for the library grid.
 *
 * CONTRACT (frozen):
 * - [load] returns a ~360 px wide bitmap of page 1 (white background, aspect of the page), or null
 *   if the document can't be rendered (password, corrupt, conversion failure). Main-safe.
 * - Two-level cache: in-memory LruCache + PNG files in cacheDir/thumbs keyed by
 *   fileName + size + lastModified (+ annotation sidecar lastModified so highlights show up).
 * - Annotations are drawn onto the thumbnail with AnnotationPainter (notes omitted).
 * - DOCX: uses DocxRenditionManager.cachedRendition, else ensureRendition. At most ONE conversion
 *   and ONE PdfRenderer open at a time across all thumbnail requests (a Mutex each).
 *
 * Failures are remembered per cache key, so a document that can't be rendered isn't retried every
 * time its card is composed — only once it changes (or is [invalidate]d). A password-protected PDF
 * gets a thumbnail from its unlocked copy once the user has unlocked it in the editor.
 *
 * Implementation notes:
 * - Only page 1 is opened (the other pages aren't measured), and a page taller than 2:1 is cut off
 *   at [MAX_THUMBNAIL_HEIGHT_PX], which is more than the card's 3:4 frame shows anyway.
 * - Conversions and PdfRenderer opens are serialised separately, so a long DOCX conversion doesn't
 *   hold up PDF thumbnails. A conversion runs in this loader's own scope:
 *   a card leaving the screen stops waiting for it but doesn't throw the work away, unless it
 *   hasn't started yet and nobody else is waiting.
 * - A marker file is kept while a background conversion runs; a document whose conversion was
 *   twice cut short by the process dying (e.g. out of native memory) isn't converted for a
 *   thumbnail again until it changes, so it can't crash the library on every launch. Opening it
 *   in the editor still converts it, and its thumbnail then comes from that rendition.
 * - The memory cache is emptied when the app's UI is hidden or memory runs low.
 */
class ThumbnailLoader(
    context: Context,
    private val storage: LibraryStorage,
    private val renditions: DocxRenditionManager,
) {
    private val cacheDir: File = context.applicationContext.cacheDir
    private val thumbsDir = File(cacheDir, ThumbnailDiskCache.DIR_NAME)
    private val markersDir = File(cacheDir, ThumbnailDiskCache.CONVERSION_MARKER_DIR_NAME)

    /** The newest thumbnail per document file name; a changed document simply replaces its entry. */
    private val memory = object : LruCache<String, CachedThumbnail>(memoryBudgetBytes()) {
        override fun sizeOf(key: String, value: CachedThumbnail): Int = value.bitmap.allocationByteCount
    }

    /** File name -> cache key whose rendering failed. */
    private val failures = ConcurrentHashMap<String, String>()

    /** Only one PdfRenderer open at a time: PDFium serialises anyway, and memory is finite. */
    private val renderMutex = Mutex()

    /** Only one DOCX conversion at a time: they are CPU and memory heavy. */
    private val conversionMutex = Mutex()

    /** Background DOCX conversions by [versionKey], queued or running. Guarded by itself. */
    private val conversions = HashMap<String, Conversion>()

    private val diskPruned = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class CachedThumbnail(val key: String, val bitmap: Bitmap)

    /** Earlier background conversions of this document version died with the process; not retried. */
    private class ConversionSkipped : Exception("Earlier conversions for a thumbnail didn't finish")

    /** A shared background conversion; [waiters] and [started] are guarded by [conversions]. */
    private class Conversion {
        lateinit var result: Deferred<File>
        var waiters = 0
        var started = false
    }

    private val trimCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            // Shown cards keep their own references; this only drops thumbnails nobody displays.
            if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) memory.evictAll()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Superseded by onTrimMemory, which handles every level that matters here")
        override fun onLowMemory() = memory.evictAll()
    }

    init {
        // The loader lives as long as the process (AppContainer), so this is never unregistered.
        context.applicationContext.registerComponentCallbacks(trimCallbacks)
    }

    suspend fun load(doc: LibraryDocument): Bitmap? = withContext(Dispatchers.IO) {
        if (diskPruned.compareAndSet(false, true)) ThumbnailDiskCache.prune(thumbsDir, MAX_DISK_FILES)
        val paths = storage.paths.value
        val key = cacheKey(doc, paths)
        fromMemory(doc.fileName, key)?.let { return@withContext it }
        if (failures[doc.fileName] == key) return@withContext null
        fromDisk(doc.fileName, key)?.let { return@withContext remember(doc.fileName, key, it) }

        // Converting first, outside the render slot, so PDF thumbnails don't queue behind a conversion.
        val source = try {
            pdfSourceOf(doc, paths)
        } catch (e: ConversionSkipped) {
            // Not remembered as a failure: once the editor has converted it, the rendition is used.
            return@withContext null
        }
        if (source == null) {
            failures[doc.fileName] = key
            return@withContext null
        }
        val rendered = renderMutex.withLock {
            // Another request for the same document may have finished while this one waited.
            fromMemory(doc.fileName, key)?.let { return@withContext it }
            if (failures[doc.fileName] == key) return@withContext null
            val bitmap = render(doc, source, paths)
            if (bitmap == null) {
                failures[doc.fileName] = key
                return@withContext null
            }
            remember(doc.fileName, key, bitmap)
        }
        toDisk(doc.fileName, key, rendered)
        rendered
    }

    /** Drops cached thumbnails for [fileName] (after rename/delete/edit). */
    fun invalidate(fileName: String) {
        memory.remove(fileName)
        failures.remove(fileName)
        scope.launch {
            ThumbnailDiskCache.deleteFor(thumbsDir, fileName)
            ThumbnailDiskCache.deleteFor(markersDir, fileName)
        }
    }

    private fun cacheKey(doc: LibraryDocument, paths: LibraryPaths): String {
        val unlockedCopy = if (doc.type == DocType.PDF) paths.renditionFile(doc.fileName).lastModified() else 0L
        return ThumbnailDiskCache.cacheKey(
            fileName = doc.fileName,
            size = doc.sizeBytes,
            lastModified = doc.lastModified,
            sidecarLastModified = paths.sidecarFile(doc.fileName).lastModified(),
            extraLastModified = unlockedCopy,
        )
    }

    /** Identity of one version of a document's content (annotations aside). */
    private fun versionKey(doc: LibraryDocument): String = "${doc.fileName}|${doc.sizeBytes}|${doc.lastModified}"

    private fun fromMemory(fileName: String, key: String): Bitmap? =
        memory.get(fileName)?.takeIf { it.key == key }?.bitmap

    private fun remember(fileName: String, key: String, bitmap: Bitmap): Bitmap {
        memory.put(fileName, CachedThumbnail(key, bitmap))
        // Uploads the texture ahead of the first draw, so cards don't hitch while scrolling.
        bitmap.prepareToDraw()
        return bitmap
    }

    private fun fromDisk(fileName: String, key: String): Bitmap? {
        val file = File(thumbsDir, ThumbnailDiskCache.fileName(fileName, key))
        if (!file.isFile) return null
        // Older versions cached whole pages of any height; one that is too big is rendered again.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outWidth > THUMBNAIL_WIDTH_PX || bounds.outHeight > MAX_THUMBNAIL_HEIGHT_PX) {
            file.delete()
            return null
        }
        val bitmap = BitmapFactory.decodeFile(file.path)
        if (bitmap == null) {
            file.delete()
            return null
        }
        // Reads count as use for the size-bounded cache, which drops the least recently used files.
        file.setLastModified(System.currentTimeMillis())
        return bitmap
    }

    private fun toDisk(fileName: String, key: String, bitmap: Bitmap) {
        val name = ThumbnailDiskCache.fileName(fileName, key)
        val target = File(thumbsDir, name)
        val temp = File(thumbsDir, ThumbnailDiskCache.tempFileName(name))
        try {
            if (!thumbsDir.isDirectory && !thumbsDir.mkdirs()) return
            val written = FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written || !temp.renameTo(target)) {
                temp.delete()
                return
            }
            // Older versions of this document's thumbnail are useless now.
            ThumbnailDiskCache.deleteFor(thumbsDir, fileName, keep = name)
        } catch (e: IOException) {
            temp.delete()
            Log.w(TAG, "Couldn't cache a thumbnail", e)
        }
    }

    /**
     * The PDF to take page 1 from: the document itself, or a DOCX's rendition (converted in the
     * background if needed). Null if a DOCX can't be converted. Throws only cancellation and
     * [ConversionSkipped].
     */
    private suspend fun pdfSourceOf(doc: LibraryDocument, paths: LibraryPaths): File? = when (doc.type) {
        DocType.PDF -> doc.file
        DocType.DOCX -> try {
            renditions.cachedRendition(paths, doc.file) ?: awaitConversion(doc, paths)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ConversionSkipped) {
            throw e
        } catch (e: Exception) {
            // A failure that coincides with the card going away must not be remembered as one.
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "No thumbnail for a Word document: ${e.message}")
            null
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "Out of memory converting a Word document for its thumbnail", e)
            null
        }
    }

    /**
     * Waits for the shared background conversion of [doc], starting one if needed. If the caller
     * stops waiting, the conversion carries on once started; one still queued is dropped when
     * nobody waits for it any more (e.g. a card scrolled past long ago).
     */
    private suspend fun awaitConversion(doc: LibraryDocument, paths: LibraryPaths): File {
        val key = versionKey(doc)
        val conversion = synchronized(conversions) {
            conversions.getOrPut(key) { startConversion(key, doc.file, paths) }.also { it.waiters++ }
        }
        try {
            return conversion.result.await()
        } finally {
            synchronized(conversions) {
                conversion.waiters--
                if (conversion.waiters == 0 && !conversion.started) {
                    conversion.result.cancel()
                    conversions.remove(key, conversion)
                }
            }
        }
    }

    /** Must hold the [conversions] lock. */
    private fun startConversion(key: String, docx: File, paths: LibraryPaths): Conversion {
        val conversion = Conversion()
        conversion.result = scope.async {
            conversionMutex.withLock {
                synchronized(conversions) { conversion.started = true }
                convertWithMarker(key, docx, paths)
            }
        }
        conversion.result.invokeOnCompletion { synchronized(conversions) { conversions.remove(key, conversion) } }
        return conversion
    }

    /** Converts [docx] behind a marker file that only survives if the process dies meanwhile. */
    private suspend fun convertWithMarker(key: String, docx: File, paths: LibraryPaths): File {
        val marker = File(markersDir, ThumbnailDiskCache.conversionMarkerName(docx.name, key))
        val attempts = ThumbnailDiskCache.markerAttempts(if (marker.isFile) readQuietly(marker) else null)
        if (attempts >= MAX_CONVERSION_ATTEMPTS) throw ConversionSkipped()
        try {
            markersDir.mkdirs()
            marker.writeText((attempts + 1).toString())
        } catch (e: IOException) {
            Log.w(TAG, "Couldn't write a conversion marker", e)
        }
        try {
            return renditions.ensureRendition(paths, docx)
        } finally {
            marker.delete()
        }
    }

    private fun readQuietly(file: File): String? = try {
        file.readText()
    } catch (e: IOException) {
        null
    }

    /** Must hold [renderMutex]. Returns null (never throws, except cancellation) if [source] can't be shown. */
    private suspend fun render(doc: LibraryDocument, source: File, paths: LibraryPaths): Bitmap? = try {
        val (bitmap, pageSize) = renderFirstPage(source, doc, paths)
        drawAnnotations(bitmap, pageSize.width, paths.sidecarFile(doc.fileName))
        bitmap
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Log.w(TAG, "No thumbnail for a ${doc.type} document: ${e.message}")
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "Out of memory rendering a ${doc.type} thumbnail", e)
        null
    }

    /** Renders [source]; a password-protected PDF falls back to its unlocked copy, if the user made one. */
    private suspend fun renderFirstPage(source: File, doc: LibraryDocument, paths: LibraryPaths): Pair<Bitmap, SizeF> = try {
        PdfDocumentHandle.renderFirstPage(source, THUMBNAIL_WIDTH_PX, MAX_THUMBNAIL_HEIGHT_PX)
    } catch (e: PdfOpenException) {
        // The same freshness rule as the editor and export.
        val unlocked = if (e.reason == PdfOpenException.Reason.PASSWORD_PROTECTED && doc.type == DocType.PDF) {
            UnlockedCopies.currentCopy(paths, doc.file)
        } else {
            null
        }
        if (unlocked == null) throw e
        PdfDocumentHandle.renderFirstPage(unlocked, THUMBNAIL_WIDTH_PX, MAX_THUMBNAIL_HEIGHT_PX)
    }

    /** Paints page 1's highlights and ink from [sidecar] onto [bitmap] (page 1 rendered [pageWidthPt] wide). */
    private fun drawAnnotations(bitmap: Bitmap, pageWidthPt: Float, sidecar: File) {
        val annotations = readFirstPageAnnotations(sidecar)
        if (annotations == null || annotations.isEmpty || pageWidthPt <= 0f) return
        val scale = bitmap.width / pageWidthPt
        val canvas = Canvas(bitmap)
        canvas.scale(scale, scale)
        AnnotationPainter().drawPage(canvas, annotations, scale, drawNotes = false)
    }

    /** Page 1 annotations from the sidecar (or its backup), null if there are none or it's unreadable. */
    private fun readFirstPageAnnotations(sidecar: File): PageAnnotations? {
        for (file in listOf(sidecar, File(sidecar.parentFile, sidecar.name + ".bak"))) {
            if (!file.isFile) continue
            try {
                return AnnotationJson.decode(file.readText(Charsets.UTF_8)).annotations.page(0)
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable sidecar for a thumbnail", e)
            }
        }
        return null
    }

    private companion object {
        const val TAG = "ThumbnailLoader"
        const val THUMBNAIL_WIDTH_PX = 360

        /** 2:1 — more than the card's 3:4 frame shows; keeps a strip-shaped page from allocating ~100 MB. */
        const val MAX_THUMBNAIL_HEIGHT_PX = 2 * THUMBNAIL_WIDTH_PX
        const val MAX_DISK_FILES = 400

        /** Unfinished (process died) background conversions of one document version before giving up. */
        const val MAX_CONVERSION_ATTEMPTS = 2

        /** ~1/16 of the heap: a few dozen thumbnails, enough for a couple of screens of the grid. */
        fun memoryBudgetBytes(): Int =
            (Runtime.getRuntime().maxMemory() / 16).coerceIn(4L * 1024 * 1024, 64L * 1024 * 1024).toInt()
    }
}
