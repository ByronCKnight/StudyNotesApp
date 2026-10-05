package com.studynotes.app.pdf

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Identity of a file's contents for cache validation: any edit changes the length or the mtime. */
internal data class FileStamp(val length: Long, val lastModified: Long) {
    companion object {
        fun of(file: File) = FileStamp(file.length(), file.lastModified())
    }
}

/**
 * Persists the page-size list of each PDF, because measuring means opening (and parsing) every page:
 * about a second for a 300-page textbook, on every open.
 *
 * One JSON file per document, named by the SHA-1 of its absolute path:
 * ```
 * { "version": 1, "path": "/storage/…/Lecture.pdf", "length": 1234, "lastModified": 1700000000000,
 *   "sizes": [w0, h0, w1, h1, …] }
 * ```
 * An entry is used only if path, length, lastModified and page count all match. Every failure
 * (missing, unreadable, corrupt, stale) reads as a miss, and write failures are reported, not
 * thrown: the cache can only ever cost a re-measure, never an open. Least recently used entries
 * beyond [maxEntries] are deleted.
 *
 * Not thread-confined: concurrent handles may read and write the same entry; writes go through a
 * unique temp file and an atomic rename, so readers see either the old or the new entry.
 */
internal class PageSizeCache(
    private val dir: File,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /** Cached sizes for the file at [path], or null unless the entry matches [stamp] and [pageCount]. */
    fun read(path: String, stamp: FileStamp, pageCount: Int): List<PageDimensions>? {
        val entry = entryFile(path)
        if (!entry.isFile) return null
        val sizes = try {
            decode(entry.readText(Charsets.UTF_8), path, stamp, pageCount)
        } catch (e: Exception) {
            null
        }
        // Touch hits so pruning drops the least recently *used* documents.
        if (sizes != null) entry.setLastModified(System.currentTimeMillis())
        return sizes
    }

    /** Stores [sizes] for the file at [path]. Returns false if the entry could not be written. */
    fun write(path: String, stamp: FileStamp, sizes: List<PageDimensions>): Boolean {
        val tmp = try {
            if (!dir.isDirectory) dir.mkdirs()
            File.createTempFile("sizes", ".tmp", dir)
        } catch (e: Exception) {
            return false
        }
        return try {
            tmp.writeText(encode(path, stamp, sizes), Charsets.UTF_8)
            moveReplacing(tmp, entryFile(path))
            prune()
            true
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete() // no-op after a successful move
        }
    }

    /** The cache file for [path]; hashed because paths contain characters invalid in file names. */
    fun entryFile(path: String): File = File(dir, sha1Hex(path) + ".json")

    private fun decode(json: String, path: String, stamp: FileStamp, pageCount: Int): List<PageDimensions>? {
        val root = JSONObject(json)
        if (root.optInt("version", -1) != VERSION) return null
        // Guards against a (theoretical) hash collision between two paths.
        if (root.optString("path") != path) return null
        if (!root.has("length") || root.getLong("length") != stamp.length) return null
        if (!root.has("lastModified") || root.getLong("lastModified") != stamp.lastModified) return null
        val flat = root.optJSONArray("sizes") ?: return null
        if (pageCount <= 0 || flat.length() != pageCount * 2) return null
        val sizes = ArrayList<PageDimensions>(pageCount)
        for (i in 0 until pageCount) {
            val width = flat.optInt(2 * i, 0)
            val height = flat.optInt(2 * i + 1, 0)
            if (width <= 0 || height <= 0) return null
            sizes += PageDimensions(width, height)
        }
        return sizes
    }

    private fun encode(path: String, stamp: FileStamp, sizes: List<PageDimensions>): String {
        val flat = JSONArray()
        for (size in sizes) {
            flat.put(size.width)
            flat.put(size.height)
        }
        return JSONObject()
            .put("version", VERSION)
            .put("path", path)
            .put("length", stamp.length)
            .put("lastModified", stamp.lastModified)
            .put("sizes", flat)
            .toString()
    }

    private fun moveReplacing(from: File, to: File) {
        try {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun prune() {
        val files = dir.listFiles() ?: return
        if (files.size <= maxEntries) return
        files.sortedByDescending { it.lastModified() }
            .drop(maxEntries)
            .forEach { it.delete() }
    }

    private fun sha1Hex(text: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val VERSION = 1
        const val DEFAULT_MAX_ENTRIES = 500
    }
}
