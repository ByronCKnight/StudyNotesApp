package com.studynotes.app.data

import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Which unlocked working copy of a password-protected PDF ([LibraryPaths.renditionFile]) is still
 * current. When the copy is made, the size and lastModified of the source it was unlocked from are
 * recorded in [LibraryPaths.renditionMetaFile] (which renames, deletes and the library's "is this
 * name taken" check already treat as part of the document). The copy is current only while the
 * source still matches exactly, so a different file later stored under the same name (copied in
 * from a PC, say) never opens, exports or thumbnails as the old document.
 *
 * Copies made before this record existed have no meta; for those the earlier rule applies (the copy
 * is at least as new as the source), so users aren't asked for passwords again after an update.
 *
 * One rule shared by the editor, the thumbnail loader and export. Blocking (small IO).
 */
internal object UnlockedCopies {

    private const val TAG = "UnlockedCopies"
    private const val KEY_KIND = "kind"
    private const val KIND = "unlockedCopy"
    private const val KEY_SOURCE_SIZE = "sourceSize"
    private const val KEY_SOURCE_LAST_MODIFIED = "sourceLastModified"

    /** The unlocked copy of [source] if there is one and it was made from [source] as it is now. */
    fun currentCopy(paths: LibraryPaths, source: File): File? {
        val copy = paths.renditionFile(source.name)
        if (!copy.isFile || !source.isFile) return null
        val current = isCurrent(
            copyLastModified = copy.lastModified(),
            meta = readMeta(paths.renditionMetaFile(source.name)),
            sourceSize = source.length(),
            sourceLastModified = source.lastModified(),
        )
        return copy.takeIf { current }
    }

    /** Forgets which source the copy came from; call before (re)creating the copy. */
    fun clearRecord(paths: LibraryPaths, sourceFileName: String) {
        paths.renditionMetaFile(sourceFileName).delete()
    }

    /**
     * Records that the copy of [sourceFileName] was made from the version with [sourceSize] and
     * [sourceLastModified] (captured before unlocking). A failure is logged: the copy then falls
     * back to the rule for copies without a record.
     */
    fun record(paths: LibraryPaths, sourceFileName: String, sourceSize: Long, sourceLastModified: Long) {
        val meta = paths.renditionMetaFile(sourceFileName)
        val json = JSONObject()
            .put(KEY_KIND, KIND)
            .put(KEY_SOURCE_SIZE, sourceSize)
            .put(KEY_SOURCE_LAST_MODIFIED, sourceLastModified)
            .toString()
        val tmp = File(meta.parentFile, meta.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(json.toByteArray(Charsets.UTF_8))
                out.flush()
                runCatching { out.fd.sync() }
            }
            if (!tmp.renameTo(meta)) {
                tmp.copyTo(meta, overwrite = true)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            Log.w(TAG, "Couldn't record the source of an unlocked copy", e)
        }
    }

    /** What a meta file says; [Unreadable] when it exists but can't be understood. */
    internal sealed interface Meta {
        data object Missing : Meta
        data object Unreadable : Meta
        data class Source(val size: Long, val lastModified: Long) : Meta
    }

    /** The freshness rule, separated from the file system for tests. */
    internal fun isCurrent(copyLastModified: Long, meta: Meta, sourceSize: Long, sourceLastModified: Long): Boolean =
        when (meta) {
            is Meta.Source -> meta.size == sourceSize && meta.lastModified == sourceLastModified
            Meta.Missing -> copyLastModified >= sourceLastModified
            Meta.Unreadable -> false
        }

    internal fun parseMeta(text: String): Meta = try {
        val o = JSONObject(text)
        if (o.has(KEY_SOURCE_SIZE) && o.has(KEY_SOURCE_LAST_MODIFIED)) {
            Meta.Source(o.getLong(KEY_SOURCE_SIZE), o.getLong(KEY_SOURCE_LAST_MODIFIED))
        } else {
            Meta.Unreadable
        }
    } catch (e: JSONException) {
        Meta.Unreadable
    }

    private fun readMeta(file: File): Meta {
        if (!file.isFile) return Meta.Missing
        return try {
            parseMeta(file.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            Meta.Unreadable
        }
    }
}
