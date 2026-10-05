package com.studynotes.app.library.data

import com.studynotes.app.data.LibraryPaths
import java.io.File

/**
 * The files the app keeps in `.studynotes` for a document, all keyed by its file name: they belong
 * to whatever document has that name, so a name that still has any of them is not free for a new
 * document (it would inherit another file's highlights, notes or unlocked copy).
 */
internal object DocumentMetadata {

    /** Every per-document file for [fileName] in [paths], whether or not it exists. */
    fun filesOf(paths: LibraryPaths, fileName: String): List<File> {
        val sidecar = paths.sidecarFile(fileName)
        return listOf(
            sidecar,
            backupOf(sidecar), // AnnotationStore's previous version
            paths.renditionFile(fileName), // DOCX rendition, or the unlocked copy of a protected PDF
            paths.renditionMetaFile(fileName),
        )
    }

    /** True when any of [fileName]'s files in [paths] exists. */
    fun exists(paths: LibraryPaths, fileName: String): Boolean = filesOf(paths, fileName).any { it.exists() }

    /** True when [fileName] has an annotation sidecar (or only its backup) in [paths]. */
    fun hasSidecar(paths: LibraryPaths, fileName: String): Boolean {
        val sidecar = paths.sidecarFile(fileName)
        return sidecar.exists() || backupOf(sidecar).exists()
    }

    /** The backup AnnotationStore keeps next to [sidecar]. */
    fun backupOf(sidecar: File): File = File(sidecar.parentFile, sidecar.name + ".bak")
}
