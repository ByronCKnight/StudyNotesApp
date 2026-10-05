package com.studynotes.app.export

import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** File bookkeeping shared by the exporter, the unlocker and [ExportActions]. */
internal object ExportFiles {
    /** Exports older than this are pruned; cloud apps that received a share have uploaded by then. */
    val MAX_EXPORT_AGE_MS: Long = TimeUnit.HOURS.toMillis(24)

    /**
     * Where the annotated export of library document [docFileName] is written: a folder of its own
     * in [exportsDir] (see ExportNaming.documentKey), under the friendly "<title> (annotated).pdf"
     * name that share sheets and cloud apps show.
     */
    fun exportTargetFor(exportsDir: File, docFileName: String, annotated: Boolean = true): File {
        val title = docFileName.substringBeforeLast('.')
        return File(
            File(exportsDir, ExportNaming.documentKey(docFileName)),
            if (annotated) ExportNaming.annotatedPdfName(title) else ExportNaming.plainPdfName(title),
        )
    }

    /** The temp file a result for [target] is written to before it is moved into place. */
    fun partFileFor(target: File): File = File(target.absoluteFile.parentFile, target.name + ".part")

    /**
     * Replaces [target] with [part]: deletes a stale target first, then renames, falling back to a
     * copy where the volume refuses renames (some FUSE-backed shared storage). Throws IOException.
     */
    fun moveIntoPlace(part: File, target: File) {
        if (target.exists() && !target.delete()) throw IOException("Cannot replace $target")
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
    }

    /**
     * Deletes files last modified more than [maxAgeMs] before [now] from [dir] and from the
     * per-document folders inside it (see ExportNaming.documentKey), except [keep]. A folder is
     * removed once it is empty and was itself untouched for [maxAgeMs] before this prune, so a folder
     * another export has just created or written to stays; [keep]'s folder always stays.
     */
    fun pruneExports(dir: File, keep: File, now: Long, maxAgeMs: Long = MAX_EXPORT_AGE_MS) {
        val keepFile = keep.absoluteFile
        val keepDir = keepFile.parentFile
        fun isOld(file: File) = now - file.lastModified() > maxAgeMs
        fun pruneFiles(folder: File) {
            folder.listFiles()?.forEach { file ->
                if (file.isFile && file.absoluteFile != keepFile && isOld(file)) file.delete()
            }
        }
        pruneFiles(dir)
        dir.listFiles()?.forEach { folder ->
            if (!folder.isDirectory) return@forEach
            // Checked before pruning: deleting the files inside updates the folder's own date.
            val untouched = isOld(folder)
            pruneFiles(folder)
            if (untouched && folder.absoluteFile != keepDir && folder.list()?.isEmpty() == true) folder.delete()
        }
    }
}
