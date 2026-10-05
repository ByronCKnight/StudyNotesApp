package com.studynotes.app.data

import android.util.Log
import com.studynotes.app.model.AnnotationFile
import com.studynotes.app.model.AnnotationFormatException
import com.studynotes.app.model.AnnotationJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Outcome of reading a sidecar. */
sealed interface SidecarLoad {
    /** No sidecar exists: the document has never been annotated. */
    data object Missing : SidecarLoad

    /**
     * [file] was read, from the backup copy when [fromBackup]. The unreadable main file was then
     * preserved like [Unreadable]'s files; any that couldn't be are listed in [unpreserved].
     */
    data class Loaded(
        val file: AnnotationFile,
        val fromBackup: Boolean,
        val unpreserved: List<File> = emptyList(),
    ) : SidecarLoad

    /**
     * A sidecar exists but neither it nor its backup could be read (damaged, or written by a newer
     * app version). Both were preserved as [preservedCopies] first, so later saves can't destroy
     * them. Files that couldn't be preserved are listed in [unpreserved]: saving over them would
     * destroy the user's only copy.
     */
    data class Unreadable(
        val preservedCopies: List<File>,
        val unpreserved: List<File> = emptyList(),
    ) : SidecarLoad
}

/**
 * Reads and writes annotation sidecars. Writes are atomic (temp file + rename) and the previous
 * version is kept as `<name>.json.bak`, which loads fall back to if the main file is unreadable.
 * All operations are serialised through one mutex so autosave and manual save never interleave.
 */
class AnnotationStore {
    private val mutex = Mutex()

    /** Saves announced via [reserveSave] that haven't finished yet, keyed by sidecar path. */
    private val pendingSaves = ConcurrentHashMap<String, MutableList<CompletableDeferred<Unit>>>()

    /** Like [loadResult] but collapses "missing" and "unreadable" into null. */
    suspend fun load(paths: LibraryPaths, fileName: String): AnnotationFile? =
        (loadResult(paths, fileName) as? SidecarLoad.Loaded)?.file

    /**
     * Reads the sidecar of [fileName], first waiting for any save announced with [reserveSave] (so a
     * document reopened right after it was closed sees its final save). [ownReservation] is the
     * caller's own reservation, which it must not wait for.
     */
    suspend fun loadResult(
        paths: LibraryPaths,
        fileName: String,
        ownReservation: CompletableDeferred<Unit>? = null,
    ): SidecarLoad {
        awaitPendingSaves(paths.sidecarFile(fileName), except = ownReservation)
        return withContext(Dispatchers.IO) {
            mutex.withLock {
                val main = paths.sidecarFile(fileName)
                val backup = backupOf(main)
                if (!main.exists() && !backup.exists()) return@withLock SidecarLoad.Missing
                read(main)?.let { return@withLock SidecarLoad.Loaded(it, fromBackup = false) }
                read(backup)?.let { previous ->
                    // The damaged main file may be the newest version (e.g. written by a newer app);
                    // the next two saves would rotate it away, so keep it like any unreadable file.
                    val kept = preserveUnreadable(main)
                    return@withLock SidecarLoad.Loaded(previous, fromBackup = true, unpreserved = kept.unpreserved)
                }
                val kept = preserveUnreadable(main, backup)
                SidecarLoad.Unreadable(kept.preserved, kept.unpreserved)
            }
        }
    }

    /**
     * True while a save of [fileName] announced with [reserveSave] is pending, which includes the
     * whole time the document is open in the editor.
     */
    fun hasPendingSave(paths: LibraryPaths, fileName: String): Boolean =
        pendingSaves.containsKey(paths.sidecarFile(fileName).absolutePath)

    /**
     * Announces saves to come. The editor holds one for as long as a document is open, up to and
     * including its final save after it closes. Loads of the same document wait until the returned
     * handle is completed, which the caller must do in a `finally` once its saves have finished or
     * failed, and the library leaves the document's files alone meanwhile ([hasPendingSave]).
     */
    fun reserveSave(paths: LibraryPaths, fileName: String): CompletableDeferred<Unit> {
        val key = paths.sidecarFile(fileName).absolutePath
        val handle = CompletableDeferred<Unit>()
        pendingSaves.compute(key) { _, list -> (list ?: mutableListOf()).apply { add(handle) } }
        handle.invokeOnCompletion {
            pendingSaves.computeIfPresent(key) { _, list -> list.apply { remove(handle) }.ifEmpty { null } }
        }
        return handle
    }

    /** Persists [file] for document [AnnotationFile.source]'s fileName. Throws IOException on failure. */
    suspend fun save(paths: LibraryPaths, file: AnnotationFile) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Encode under the lock so a reader can never overtake a writer that has already started.
            val json = AnnotationJson.encode(file)
            paths.ensureDirs()
            val target = paths.sidecarFile(file.source.fileName)
            writeAtomically(target, json.toByteArray(Charsets.UTF_8))
        }
    }

    suspend fun delete(paths: LibraryPaths, fileName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val main = paths.sidecarFile(fileName)
            main.delete()
            backupOf(main).delete()
        }
    }

    /**
     * Moves the sidecar and its backup along with a renamed document. Old files are removed only
     * once their contents exist under the new name; throws IOException if a move fails so callers
     * can roll back.
     */
    suspend fun rename(paths: LibraryPaths, oldFileName: String, newFileName: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val oldMain = paths.sidecarFile(oldFileName)
            val newMain = paths.sidecarFile(newFileName)
            if (oldMain.absolutePath == newMain.absolutePath) return@withLock
            val oldBak = backupOf(oldMain)
            val newBak = backupOf(newMain)
            if (!oldMain.exists() && !oldBak.exists()) return@withLock
            newMain.delete()
            newBak.delete()
            val movedMain = oldMain.exists()
            if (movedMain) move(oldMain, newMain)
            if (oldBak.exists()) {
                try {
                    move(oldBak, newBak)
                } catch (e: IOException) {
                    // Undo the half that succeeded, so the caller's rollback finds the sidecar where
                    // the document will be again (the old name), not stranded under the new one.
                    if (movedMain && !oldMain.exists()) {
                        try {
                            move(newMain, oldMain)
                        } catch (restore: IOException) {
                            e.addSuppressed(restore)
                        }
                    }
                    throw e
                }
            }
        }
    }

    private suspend fun awaitPendingSaves(sidecar: File, except: CompletableDeferred<Unit>?) {
        val waiting = pendingSaves[sidecar.absolutePath]?.toList().orEmpty()
        for (handle in waiting) if (handle !== except) handle.await()
    }

    private fun read(f: File): AnnotationFile? {
        if (!f.isFile) return null
        return try {
            AnnotationJson.decode(f.readText(Charsets.UTF_8))
        } catch (e: IOException) {
            Log.w(TAG, "Unreadable sidecar ${f.path}", e)
            null
        } catch (e: AnnotationFormatException) {
            Log.w(TAG, "Unreadable sidecar ${f.path}", e)
            null
        } catch (e: JSONException) {
            Log.w(TAG, "Unreadable sidecar ${f.path}", e)
            null
        }
    }

    private class Preserved(val preserved: List<File>, val unpreserved: List<File>)

    /**
     * Copies unreadable sidecar files to `<name>.unreadable-<lastModified>` — names that saves
     * never rotate or delete — so the user's data survives until it can be recovered. Repeated
     * loads of the same damaged file reuse the existing copy. If copying fails (a full disk, say)
     * the file is renamed there instead, which needs no space.
     */
    private fun preserveUnreadable(vararg files: File): Preserved {
        val preserved = mutableListOf<File>()
        val unpreserved = mutableListOf<File>()
        for (f in files) {
            if (!f.isFile) continue
            val copy = File(f.parentFile, "${f.name}.unreadable-${f.lastModified()}")
            if (copy.isFile && copy.length() == f.length()) {
                preserved += copy
                continue
            }
            try {
                preserved += f.copyTo(copy, overwrite = true)
            } catch (e: IOException) {
                copy.delete()
                if (f.renameTo(copy)) {
                    preserved += copy
                } else {
                    Log.w(TAG, "Couldn't preserve ${f.path}", e)
                    unpreserved += f
                }
            }
        }
        return Preserved(preserved, unpreserved)
    }

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val dir = target.parentFile ?: throw IOException("No parent for $target")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create $dir")
        val tmp = File(dir, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        val backup = backupOf(target)
        if (target.exists()) {
            backup.delete()
            if (!target.renameTo(backup)) target.copyTo(backup, overwrite = true)
        }
        if (!tmp.renameTo(target)) {
            // Some FUSE-backed volumes refuse rename-over; fall back to a direct copy.
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun move(from: File, to: File) {
        if (from.renameTo(to)) return
        from.copyTo(to, overwrite = true)
        if (!from.delete()) throw IOException("Couldn't remove ${from.path} after copying it")
    }

    private fun backupOf(main: File) = File(main.parentFile, main.name + ".bak")

    private companion object {
        const val TAG = "AnnotationStore"
    }
}
