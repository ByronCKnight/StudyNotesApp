package com.studynotes.app.docx

import android.content.Context
import com.studynotes.app.data.LibraryPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class DocxConversionException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Converts .docx files into PDF "renditions" that the PDF pipeline (viewer, annotations, export)
 * works on. Renditions live in [LibraryPaths.renditionFile]; bookkeeping (source size, source
 * lastModified, converter version) in [LibraryPaths.renditionMetaFile].
 *
 * CONTRACT (frozen):
 * - A rendition is reused while the source's size+lastModified match the meta file, EVEN IF the
 *   converter version changed (so existing annotations keep lining up). It is regenerated when the
 *   source changed or the rendition/meta is missing or unreadable.
 * - Conversion writes to a temp file then renames, so a crash never leaves a half-written rendition.
 * - Concurrent calls for the same document are serialised (one conversion at a time per file).
 */
class DocxRenditionManager(context: Context) {

    /** One mutex per rendition path; entries are tiny and bounded by the number of documents. */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(file: File): Mutex = locks.computeIfAbsent(file.absolutePath) { Mutex() }

    /**
     * Returns an up-to-date PDF rendition for [docx], converting if needed (CPU/IO heavy; runs on
     * background dispatchers; safe to call from the main thread). Throws [DocxConversionException].
     */
    suspend fun ensureRendition(paths: LibraryPaths, docx: File): File {
        val rendition = paths.renditionFile(docx.name)
        val metaFile = paths.renditionMetaFile(docx.name)
        return lockFor(rendition).withLock {
            val upToDate = withContext(Dispatchers.IO) {
                if (!docx.isFile) throw DocxConversionException("This file couldn't be found.")
                isUpToDate(rendition, metaFile, docx)
            }
            if (upToDate) return@withLock rendition

            val previous = withContext(Dispatchers.IO) {
                paths.ensureDirs()
                readMeta(metaFile)?.also {
                    // Meta that already matches the source (the PDF itself is missing) would vouch for
                    // whatever a crash leaves behind, so drop it. Mismatching meta stays: it can never
                    // validate anything, and it remembers which version the old annotations belong to.
                    if (it.matches(docx.length(), docx.lastModified())) metaFile.delete()
                }
            }
            // Capture the source version before converting: if it changes meanwhile, the meta won't
            // match and the next call converts again.
            val (size, lastModified) = withContext(Dispatchers.IO) { docx.length() to docx.lastModified() }
            withContext(Dispatchers.Default) {
                val work = coroutineContext
                DocxConverter.convert(docx, rendition) { work.ensureActive() }
            }
            val replaced = previous?.takeIf { !it.matches(size, lastModified) }
            val meta = RenditionMeta(
                sourceSize = size,
                sourceLastModified = lastModified,
                converterVersion = DocxConverter.VERSION,
                createdAt = System.currentTimeMillis(),
                replacedSourceSize = replaced?.sourceSize ?: previous?.replacedSourceSize,
                replacedSourceLastModified = replaced?.sourceLastModified ?: previous?.replacedSourceLastModified,
            )
            withContext(Dispatchers.IO) {
                try {
                    writeMeta(metaFile, meta)
                } catch (e: IOException) {
                    throw DocxConversionException(DocxConverter.SAVE_FAILED_MESSAGE, e)
                }
            }
            rendition
        }
    }

    /** The rendition if it exists and is up to date, else null. Never converts. Blocking (small IO). */
    fun cachedRendition(paths: LibraryPaths, docx: File): File? {
        val rendition = paths.renditionFile(docx.name)
        return rendition.takeIf { isUpToDate(it, paths.renditionMetaFile(docx.name), docx) }
    }

    /**
     * True when the existing rendition was made from a different version of the source file: either
     * the rendition is stale (source edited since), or it was regenerated because the source changed
     * and that hasn't been [acknowledged][acknowledgeSourceChange] yet. Annotations made on the older
     * rendition may then be misaligned. Blocking (small IO).
     */
    fun isSourceChanged(paths: LibraryPaths, docx: File): Boolean {
        val meta = readMeta(paths.renditionMetaFile(docx.name)) ?: return false
        return !meta.matches(docx.length(), docx.lastModified()) || meta.replacedOtherSource
    }

    /**
     * Records that the user has been told about a source change, so [isSourceChanged] stops
     * reporting it for the current rendition.
     */
    suspend fun acknowledgeSourceChange(paths: LibraryPaths, fileName: String) {
        val rendition = paths.renditionFile(fileName)
        lockFor(rendition).withLock {
            withContext(Dispatchers.IO) {
                val metaFile = paths.renditionMetaFile(fileName)
                val meta = readMeta(metaFile) ?: return@withContext
                if (meta.replacedOtherSource) {
                    runCatching { writeMeta(metaFile, meta.copy(replacedSourceSize = null, replacedSourceLastModified = null)) }
                }
            }
        }
    }

    suspend fun delete(paths: LibraryPaths, fileName: String) {
        val rendition = paths.renditionFile(fileName)
        lockFor(rendition).withLock {
            withContext(Dispatchers.IO) {
                val meta = paths.renditionMetaFile(fileName)
                for (f in listOf(rendition, meta, tempOf(rendition), tempOf(meta))) f.delete()
            }
        }
    }

    suspend fun rename(paths: LibraryPaths, oldFileName: String, newFileName: String) {
        if (oldFileName == newFileName) return
        val oldRendition = paths.renditionFile(oldFileName)
        val newRendition = paths.renditionFile(newFileName)
        // Lock both documents in a fixed order so two opposite renames can't deadlock.
        val (first, second) = listOf(oldRendition, newRendition).sortedBy { it.absolutePath }
        lockFor(first).withLock {
            lockFor(second).withLock {
                withContext(Dispatchers.IO) {
                    val oldMeta = paths.renditionMetaFile(oldFileName)
                    val newMeta = paths.renditionMetaFile(newFileName)
                    // The rendition and its meta describe one file, so they move as a pair. A meta on
                    // its own must not displace the new name's own rendition (an unlocked copy, a
                    // converted DOCX); it only moves where there is no rendition to mismatch.
                    if (oldRendition.exists()) {
                        move(oldRendition, newRendition)
                        move(oldMeta, newMeta)
                    } else if (oldMeta.exists() && !newRendition.exists()) {
                        move(oldMeta, newMeta)
                    }
                }
            }
        }
    }

    private fun isUpToDate(rendition: File, metaFile: File, docx: File): Boolean {
        if (!rendition.isFile || rendition.length() == 0L || !docx.isFile) return false
        val meta = readMeta(metaFile) ?: return false
        return meta.matches(docx.length(), docx.lastModified())
    }

    private fun readMeta(metaFile: File): RenditionMeta? {
        if (!metaFile.isFile) return null
        return try {
            RenditionMeta.decode(metaFile.readText(Charsets.UTF_8))
        } catch (_: IOException) {
            null
        }
    }

    private fun writeMeta(metaFile: File, meta: RenditionMeta) {
        val tmp = tempOf(metaFile)
        FileOutputStream(tmp).use { out ->
            out.write(meta.encode().toByteArray(Charsets.UTF_8))
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(metaFile)) {
            metaFile.delete()
            if (!tmp.renameTo(metaFile)) {
                tmp.copyTo(metaFile, overwrite = true)
                tmp.delete()
            }
        }
    }

    /** Replaces [to] with [from]; a missing [from] just clears [to]. A failed copy keeps [from]. */
    private fun move(from: File, to: File) {
        to.delete()
        if (!from.exists()) return
        if (!from.renameTo(to)) {
            if (runCatching { from.copyTo(to, overwrite = true) }.isSuccess) from.delete() else to.delete()
        }
    }

    private fun tempOf(file: File) = File(file.parentFile, file.name + ".tmp")
}
