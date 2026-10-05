package com.studynotes.app.data

import java.io.File

/**
 * Layout of the library folder:
 * ```
 * <root>/                         user's PDFs and DOCX files (flat, no sub-folders)
 * <root>/.studynotes/.nomedia
 * <root>/.studynotes/annotations/<fileName>.json      annotation sidecars
 * <root>/.studynotes/renditions/<fileName>.pdf        DOCX → PDF conversions
 * <root>/.studynotes/renditions/<fileName>.meta.json  conversion bookkeeping
 * ```
 * Keeping app data inside the library folder means copying the folder backs up the notes too.
 */
class LibraryPaths(
    val root: File,
    /** True when [root] is the shared Documents/StudyNotes folder; false for the app-private fallback. */
    val isPublic: Boolean,
) {
    val metaDir: File get() = File(root, META_DIR_NAME)
    val annotationsDir: File get() = File(metaDir, "annotations")
    val renditionsDir: File get() = File(metaDir, "renditions")

    fun sidecarFile(fileName: String): File = File(annotationsDir, "$fileName.json")
    fun renditionFile(fileName: String): File = File(renditionsDir, "$fileName.pdf")
    fun renditionMetaFile(fileName: String): File = File(renditionsDir, "$fileName.meta.json")

    /** Creates the folder structure if missing. Returns false if the root cannot be created. */
    fun ensureDirs(): Boolean {
        if (!root.isDirectory && !root.mkdirs()) return false
        annotationsDir.mkdirs()
        renditionsDir.mkdirs()
        val noMedia = File(metaDir, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
        return root.isDirectory
    }

    /** Human-readable location shown in the UI, e.g. "Documents/StudyNotes". */
    val displayPath: String
        get() = if (isPublic) "Documents/$PUBLIC_FOLDER_NAME" else "App storage (private)"

    override fun equals(other: Any?): Boolean =
        other is LibraryPaths && other.root == root && other.isPublic == isPublic

    override fun hashCode(): Int = root.hashCode() * 31 + isPublic.hashCode()

    companion object {
        const val PUBLIC_FOLDER_NAME = "StudyNotes"
        const val META_DIR_NAME = ".studynotes"
    }
}
