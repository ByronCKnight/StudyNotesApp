package com.studynotes.app.model

import java.io.File

enum class DocType(val extension: String, val mimeType: String) {
    PDF("pdf", "application/pdf"),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    companion object {
        fun fromFileName(name: String): DocType? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { it.extension == ext }
        }

        fun fromMimeType(mime: String?): DocType? = entries.firstOrNull { it.mimeType == mime }

        val ALL_MIME_TYPES: Array<String> = entries.map { it.mimeType }.toTypedArray()
    }
}

/** A document in the library folder. */
data class LibraryDocument(
    val file: File,
    val type: DocType,
    val sizeBytes: Long,
    val lastModified: Long,
    /** Epoch millis the document was last opened in the editor, or null if never. */
    val lastOpened: Long?,
    /** Number of highlights/strokes + notes saved for this document (0 if none). */
    val annotationCount: Int,
) {
    /** File name including extension, e.g. "Lecture 3.pdf". Unique within the library. */
    val fileName: String get() = file.name

    /** Name without extension, for display. */
    val title: String get() = file.nameWithoutExtension
}
