package com.studynotes.app.library.data

import com.studynotes.app.model.AnnotationJson
import java.io.File

/**
 * Number of annotations (strokes + notes) per sidecar, for the badges on library cards. Decoding a
 * sidecar is the expensive part of listing the library, so results are cached until the sidecar's
 * (or its backup's) size or modification time changes. Thread-safe; call off the main thread.
 */
internal class AnnotationCountCache {
    private class Entry(val signature: String, val count: Int)

    private val entries = HashMap<String, Entry>()

    /**
     * Strokes + notes stored in [sidecar], falling back to [backup] (the previous version, which is
     * all there is for a moment during an atomic save) when the main file is missing or unreadable.
     * Returns 0 when neither can be decoded.
     */
    @Synchronized
    fun count(sidecar: File, backup: File): Int {
        val signature = signatureOf(sidecar) + "|" + signatureOf(backup)
        entries[sidecar.path]?.let { if (it.signature == signature) return it.count }
        val count = decodeCount(sidecar) ?: decodeCount(backup) ?: 0
        entries[sidecar.path] = Entry(signature, count)
        return count
    }

    /** Forgets every sidecar not in [sidecarPaths] (documents that were renamed or deleted). */
    @Synchronized
    fun retainOnly(sidecarPaths: Set<String>) {
        entries.keys.retainAll(sidecarPaths)
    }

    private fun signatureOf(file: File): String =
        if (file.isFile) "${file.length()}@${file.lastModified()}" else "-"

    private fun decodeCount(file: File): Int? {
        if (!file.isFile) return null
        return try {
            val annotations = AnnotationJson.decode(file.readText(Charsets.UTF_8)).annotations
            annotations.strokeCount + annotations.noteCount
        } catch (e: Exception) {
            null
        }
    }
}
