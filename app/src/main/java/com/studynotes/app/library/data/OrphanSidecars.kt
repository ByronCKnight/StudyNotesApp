package com.studynotes.app.library.data

import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.model.AnnotationJson
import com.studynotes.app.model.DocType
import com.studynotes.app.model.SourceInfo
import java.io.File

/** What the library listing knows about one document file. */
internal data class ListedDocument(
    val fileName: String,
    val type: DocType,
    val size: Long,
    val lastModified: Long,
)

/** An annotation sidecar kept under [fileName], a name no document in the library has any more. */
internal data class OrphanedSidecar(val fileName: String, val source: SourceInfo)

/**
 * Finds the annotations of documents renamed outside the app (Files app, a PC over USB). Sidecars
 * are keyed by file name, so such a document shows up without annotations while its old sidecar
 * stays behind as an orphan. Every sidecar records the type and size of the file it was made for,
 * which a rename keeps, so an orphan that matches exactly one unannotated document (and no other
 * document matches it) belongs to that document.
 *
 * Plain java.io, so it is unit-testable. Not thread-safe: the repository calls it under its lock.
 */
internal class OrphanSidecars {

    private class CachedSource(val signature: String, val source: SourceInfo?)

    /** Decoded [SourceInfo] per orphan sidecar path, valid while the files' size/time are unchanged. */
    private val sources = HashMap<String, CachedSource>()

    /**
     * Orphan name -> document name for every orphaned sidecar in [paths] that unambiguously
     * belongs to one of [documents] (all documents in the library) that has no sidecar of its own.
     */
    fun reattachments(paths: LibraryPaths, documents: List<ListedDocument>): Map<String, String> {
        val orphanNames = orphanNames(paths.annotationsDir, documents.mapTo(HashSet()) { LibraryFileNames.nameKey(it.fileName) })
        sources.keys.retainAll(orphanNames.mapTo(HashSet()) { paths.sidecarFile(it).path })
        if (orphanNames.isEmpty()) return emptyMap()
        val unannotated = documents.filterNot { DocumentMetadata.hasSidecar(paths, it.fileName) }
        if (unannotated.isEmpty()) return emptyMap()
        val orphans = orphanNames.mapNotNull { name -> sourceOf(paths, name)?.let { OrphanedSidecar(name, it) } }
        return match(unannotated, orphans)
    }

    /** The source recorded in [fileName]'s sidecar (or its backup), or null if neither is readable. */
    private fun sourceOf(paths: LibraryPaths, fileName: String): SourceInfo? {
        val sidecar = paths.sidecarFile(fileName)
        val backup = DocumentMetadata.backupOf(sidecar)
        val signature = signatureOf(sidecar) + "|" + signatureOf(backup)
        sources[sidecar.path]?.let { if (it.signature == signature) return it.source }
        val source = decodeSource(sidecar) ?: decodeSource(backup)
        sources[sidecar.path] = CachedSource(signature, source)
        return source
    }

    private fun signatureOf(file: File): String =
        if (file.isFile) "${file.length()}@${file.lastModified()}" else "-"

    private fun decodeSource(file: File): SourceInfo? {
        if (!file.isFile) return null
        return try {
            AnnotationJson.decode(file.readText(Charsets.UTF_8)).source
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val SIDECAR_SUFFIX = ".json"
        private const val BACKUP_SUFFIX = ".json.bak"

        /**
         * Names of documents that have a sidecar (or just its backup) in [annotationsDir] but are
         * not in the library. [documentNameKeys] holds [LibraryFileNames.nameKey] of every document,
         * because shared storage matches names case-insensitively.
         */
        fun orphanNames(annotationsDir: File, documentNameKeys: Set<String>): List<String> {
            val files = annotationsDir.list() ?: return emptyList()
            return files.mapNotNull { name ->
                when {
                    name.endsWith(BACKUP_SUFFIX) -> name.dropLast(BACKUP_SUFFIX.length)
                    name.endsWith(SIDECAR_SUFFIX) -> name.dropLast(SIDECAR_SUFFIX.length)
                    else -> null
                }
            }
                .filter { LibraryFileNames.isLibraryDocumentName(it) && LibraryFileNames.nameKey(it) !in documentNameKeys }
                .distinct()
                .sorted()
        }

        /**
         * Pairs [orphans] with [documents] (which must have no sidecar) of the same type and size.
         * A pair is made only when it is unambiguous: one orphan and one document with that type and
         * size, or, when there are several, one of each that also has the same modification time
         * (a rename keeps it). Returns orphan name -> document name.
         */
        fun match(documents: List<ListedDocument>, orphans: List<OrphanedSidecar>): Map<String, String> {
            val result = LinkedHashMap<String, String>()
            val documentsByContent = documents.groupBy { it.type to it.size }
            for ((content, sameContent) in orphans.groupBy { it.source.type to it.source.size }) {
                val candidates = documentsByContent[content] ?: continue
                if (sameContent.size == 1 && candidates.size == 1) {
                    result[sameContent.single().fileName] = candidates.single().fileName
                    continue
                }
                val candidatesByTime = candidates.groupBy { it.lastModified }
                for ((time, orphansAtTime) in sameContent.groupBy { it.source.lastModified }) {
                    val documentsAtTime = candidatesByTime[time] ?: continue
                    if (orphansAtTime.size == 1 && documentsAtTime.size == 1) {
                        result[orphansAtTime.single().fileName] = documentsAtTime.single().fileName
                    }
                }
            }
            return result
        }
    }
}
