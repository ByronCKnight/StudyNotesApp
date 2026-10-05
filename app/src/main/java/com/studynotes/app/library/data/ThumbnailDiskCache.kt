package com.studynotes.app.library.data

import java.io.File
import java.security.MessageDigest

/**
 * File naming and housekeeping for the PNG thumbnail cache (`cacheDir/thumbs`).
 *
 * A thumbnail's identity ("cache key") is the document's file name, size and modification time plus
 * the modification time of its annotation sidecar, so a changed document or newly saved highlights
 * produce a new key. On disk the key becomes `<hash(fileName)>-<hash(key)>.png`: the hashes keep
 * arbitrary names file-system safe, and the shared per-document prefix lets all versions of one
 * document's thumbnail be found and dropped together.
 */
internal object ThumbnailDiskCache {
    /** Folder name under the app's cacheDir. */
    const val DIR_NAME = "thumbs"

    /** Folder (under cacheDir) of the markers of running background DOCX conversions. */
    const val CONVERSION_MARKER_DIR_NAME = "thumbs-converting"

    private const val EXTENSION = ".png"
    private const val TEMP_SUFFIX = ".tmp"
    private const val MARKER_EXTENSION = ".converting"

    /**
     * Cache key for one version of a document's first-page thumbnail. [extraLastModified] covers
     * other inputs (the unlocked copy of a password-protected PDF); 0 when absent.
     */
    fun cacheKey(
        fileName: String,
        size: Long,
        lastModified: Long,
        sidecarLastModified: Long,
        extraLastModified: Long = 0L,
    ): String = "$fileName|$size|$lastModified|$sidecarLastModified|$extraLastModified"

    /** On-disk file name for [cacheKey] of the document called [fileName]. */
    fun fileName(fileName: String, cacheKey: String): String = prefix(fileName) + hash(cacheKey, 24) + EXTENSION

    /** Name of the temp file a thumbnail is written to before being renamed into place. */
    fun tempFileName(diskName: String): String = diskName + TEMP_SUFFIX

    /**
     * Name of the marker file kept (in its own folder, see [CONVERSION_MARKER_DIR_NAME]) while a
     * background DOCX conversion of version [versionKey] of [fileName] runs. Shares the document's
     * prefix, so [deleteFor] on that folder drops it with the document.
     */
    fun conversionMarkerName(fileName: String, versionKey: String): String =
        prefix(fileName) + hash(versionKey, 24) + MARKER_EXTENSION

    /**
     * Unfinished attempts recorded in a conversion marker's [content]; 0 for a missing or garbled
     * marker. A marker outlives its attempt only when the process died during the conversion.
     */
    fun markerAttempts(content: String?): Int = content?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /** Deletes every cached thumbnail of [fileName] in [dir], except the file named [keep]. */
    fun deleteFor(dir: File, fileName: String, keep: String? = null) {
        val prefix = prefix(fileName)
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name != keep }?.forEach { it.delete() }
    }

    /**
     * Keeps the cache bounded: removes abandoned temp files and, beyond [maxFiles], the least
     * recently used thumbnails (reads refresh a file's modification time).
     */
    fun prune(dir: File, maxFiles: Int) {
        val files = dir.listFiles() ?: return
        val (temps, thumbs) = files.partition { it.name.endsWith(TEMP_SUFFIX) }
        temps.forEach { it.delete() }
        if (thumbs.size <= maxFiles) return
        thumbs.sortedBy { it.lastModified() }.take(thumbs.size - maxFiles).forEach { it.delete() }
    }

    private fun prefix(fileName: String): String = hash(fileName, 16) + "-"

    private fun hash(text: String, hexChars: Int): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(hexChars)
        for (b in digest) {
            if (sb.length >= hexChars) break
            sb.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.substring(0, hexChars)
    }

    private const val HEX = "0123456789abcdef"
}
