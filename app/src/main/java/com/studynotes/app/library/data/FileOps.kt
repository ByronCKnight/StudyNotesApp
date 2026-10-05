package com.studynotes.app.library.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * Careful file moves for the library. Shared storage, app-specific storage and internal storage can
 * be different volumes (rename fails with EXDEV), and FUSE may refuse some renames, so every move
 * falls back to a verified copy, and nothing is deleted before its copy is known to be complete.
 */
internal object FileOps {
    /** Suffix of the hidden temp file a copy is written to before it gets its final name. */
    const val COPY_SUFFIX = ".copying"

    private const val BUFFER_SIZE = 64 * 1024

    /**
     * Moves [src] to [dst] (which must not exist): rename, else copy + verify + delete the source.
     * Returns false with [src] untouched and no [dst] left behind if anything fails.
     */
    fun move(src: File, dst: File): Boolean {
        if (!src.isFile || dst.exists()) return false
        if (src.renameTo(dst)) return true
        if (!copy(src, dst)) return false
        if (src.delete()) return true
        // The source can't be removed: keep it as the only copy rather than duplicating the file.
        dst.delete()
        return false
    }

    /**
     * Copies [src] to [dst] (which must not exist) through a hidden temp file in the same folder,
     * verifies the size and keeps the modification time (DOCX renditions and annotation sidecars are
     * matched to their document by size + lastModified). Returns false and cleans up on failure.
     */
    fun copy(src: File, dst: File): Boolean {
        if (!src.isFile || dst.exists()) return false
        val dir = dst.parentFile ?: return false
        if (!dir.isDirectory && !dir.mkdirs()) return false
        val tmp = File(dir, "." + dst.name + COPY_SUFFIX)
        return try {
            src.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out, BUFFER_SIZE)
                    runCatching { out.fd.sync() }
                }
            }
            if (tmp.length() != src.length()) {
                tmp.delete()
                return false
            }
            // Not every volume supports setting the time; a newer time only costs a re-render.
            tmp.setLastModified(src.lastModified())
            if (dst.exists() || !tmp.renameTo(dst)) {
                tmp.delete()
                return false
            }
            true
        } catch (e: IOException) {
            tmp.delete()
            false
        }
    }

    /** True when [a] and [b] are both readable files with identical bytes. */
    fun sameContent(a: File, b: File): Boolean {
        if (!a.isFile || !b.isFile || a.length() != b.length()) return false
        return try {
            a.inputStream().use { inA -> b.inputStream().use { inB -> streamsEqual(inA, inB) } }
        } catch (e: IOException) {
            false
        }
    }

    private fun streamsEqual(a: InputStream, b: InputStream): Boolean {
        val bufA = ByteArray(BUFFER_SIZE)
        val bufB = ByteArray(BUFFER_SIZE)
        while (true) {
            val n = a.readUpTo(bufA)
            if (n != b.readUpTo(bufB)) return false
            if (n == 0) return true
            for (i in 0 until n) if (bufA[i] != bufB[i]) return false
        }
    }
}

/** Reads until [buffer] is full or the stream ends; returns the number of bytes read. */
internal fun InputStream.readUpTo(buffer: ByteArray): Int {
    var total = 0
    while (total < buffer.size) {
        val n = read(buffer, total, buffer.size - total)
        if (n < 0) break
        total += n
    }
    return total
}
