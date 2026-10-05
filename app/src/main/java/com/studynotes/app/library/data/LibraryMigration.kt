package com.studynotes.app.library.data

import com.studynotes.app.data.LibraryPaths
import java.io.File

/** A document moved by [LibraryMigration]; [newFile] has another name when the old one was taken. */
data class MigratedDocument(val oldName: String, val newFile: File)

/** Outcome of [LibraryMigration.migrate]. [failed] holds the names of documents left in the source. */
data class MigrationResult(val moved: List<MigratedDocument>, val failed: List<String>)

/**
 * Moves a library (documents plus their `.studynotes` data) into another library folder. Used when
 * the app gains access to the shared Documents/StudyNotes folder after running from private storage.
 *
 * Each document moves all-or-nothing: its sidecar (+ backup) and rendition (+ meta) are copied
 * first, then the document is moved, and only then are the source copies of that data deleted. Any
 * failure rolls the document back so it stays complete in the source library, where the next
 * migration attempt finds it again. Nothing in the target is ever overwritten: a document whose name
 * (or whose sidecar / rendition name) is taken gets "Name (2).ext", and its data moves with it.
 */
internal object LibraryMigration {
    private const val NO_MEDIA = ".nomedia"

    fun migrate(from: LibraryPaths, to: LibraryPaths): MigrationResult {
        val documents = documentsIn(from.root)
        if (!to.ensureDirs()) return MigrationResult(emptyList(), documents.map { it.name })

        val moved = ArrayList<MigratedDocument>()
        val failed = ArrayList<String>()
        for (doc in documents) {
            val newName = freeName(doc.name, to)
            if (moveDocument(doc, newName, from, to)) {
                moved += MigratedDocument(doc.name, File(to.root, newName))
            } else {
                failed += doc.name
            }
        }
        // Leftover data (e.g. sidecars of documents deleted from a PC) only moves once no document
        // remains behind: a document still in the source needs its data next to it.
        if (failed.isEmpty() && documentsIn(from.root).isEmpty()) sweepMetadata(from.metaDir, to.metaDir)
        return MigrationResult(moved, failed)
    }

    /** The documents directly inside [root], as the library lists them. */
    fun documentsIn(root: File): List<File> =
        root.listFiles()?.filter { it.isFile && LibraryFileNames.isLibraryDocumentName(it.name) }.orEmpty()

    private fun freeName(fileName: String, to: LibraryPaths): String {
        val taken = (to.root.list() ?: emptyArray()).mapTo(HashSet(), LibraryFileNames::nameKey)
        val isTaken = { name: String ->
            LibraryFileNames.nameKey(name) in taken || DocumentMetadata.exists(to, name)
        }
        if (!isTaken(fileName)) return fileName
        val extension = fileName.substringAfterLast('.')
        return LibraryFileNames.uniqueFileName(fileName.dropLast(extension.length + 1), extension, isTaken)
    }

    private fun moveDocument(doc: File, newName: String, from: LibraryPaths, to: LibraryPaths): Boolean {
        val pairs = DocumentMetadata.filesOf(from, doc.name).zip(DocumentMetadata.filesOf(to, newName)).filter { it.first.isFile }
        val copied = ArrayList<File>(pairs.size)
        for ((source, target) in pairs) {
            if (!FileOps.copy(source, target)) {
                copied.forEach(File::delete)
                return false
            }
            copied += target
        }
        if (!FileOps.move(doc, File(to.root, newName))) {
            copied.forEach(File::delete)
            return false
        }
        pairs.forEach { it.first.delete() }
        return true
    }

    private fun sweepMetadata(fromMeta: File, toMeta: File) {
        if (!fromMeta.isDirectory) return
        val files = fromMeta.walkTopDown().filter { it.isFile && it != File(fromMeta, NO_MEDIA) }.toList()
        for (file in files) {
            val target = File(toMeta, file.relativeTo(fromMeta).path)
            if (target.exists()) continue
            target.parentFile?.mkdirs()
            FileOps.move(file, target)
        }
        if (fromMeta.walkTopDown().none { it.isFile && it != File(fromMeta, NO_MEDIA) }) {
            File(fromMeta, NO_MEDIA).delete()
        }
        // Only empty folders can be deleted, so this removes exactly what was emptied.
        fromMeta.walkBottomUp().filter { it.isDirectory }.forEach { it.delete() }
    }
}
