package com.studynotes.app.library.data

import com.studynotes.app.model.DocType
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Why one incoming document could not be added to the library. */
enum class ImportProblem {
    UNSUPPORTED_TYPE,
    OLD_WORD_FORMAT,
    PROTECTED_WORD_FILE,
    EMPTY_FILE,

    /** A virtual document (e.g. Google Sheets) whose provider offers neither PDF nor DOCX. */
    NOT_CONVERTIBLE,
    READ_FAILED,
    NO_PERMISSION,
    NO_SPACE,
    WRITE_FAILED,
    LIBRARY_UNAVAILABLE,
    UNEXPECTED,
}

/** Thrown by the import steps; [problem] becomes the short reason shown to the user. */
class ImportException(val problem: ImportProblem, cause: Throwable? = null) :
    Exception("Import failed: $problem", cause)

/** A document now in the library. [reused] = an identical file was already there. */
data class StoredDocument(val file: File, val reused: Boolean)

/**
 * The provider-independent half of an import, in plain java.io so it can be unit tested:
 * [download] writes the incoming bytes to a hidden ".part" file in the library folder, [finalize]
 * validates the content, reuses a byte-identical document or picks a free name, and renames.
 */
internal object LibraryImporter {
    private const val BUFFER_SIZE = 64 * 1024
    private const val MAX_DISPLAY_NAME_CHARS = 60

    /** Attempts at the final rename before giving up (a name can be taken by a file we can't see). */
    private const val RENAME_ATTEMPTS = 5

    /**
     * Copies [input] into [part] (overwriting a stale one). [checkCancelled] runs between buffers
     * and may throw to abort. On any failure the part file is deleted and the error is reported as
     * an [ImportException]: read errors -> READ_FAILED / NO_PERMISSION, write errors -> NO_SPACE /
     * WRITE_FAILED, nothing read -> EMPTY_FILE. Returns the number of bytes written.
     */
    fun download(input: InputStream, part: File, checkCancelled: () -> Unit): Long {
        var total = 0L
        try {
            FileOutputStream(part).use { out ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkCancelled()
                    val n = readFromSource(input, buffer)
                    if (n < 0) break
                    try {
                        out.write(buffer, 0, n)
                    } catch (e: IOException) {
                        throw ImportException(writeProblem(e), e)
                    }
                    total += n
                }
                runCatching { out.fd.sync() }
            }
        } catch (e: ImportException) {
            part.delete()
            throw e
        } catch (e: IOException) {
            // Creating, flushing or closing the part file failed.
            part.delete()
            throw ImportException(writeProblem(e), e)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
        if (total == 0L) {
            part.delete()
            throw ImportException(ImportProblem.EMPTY_FILE)
        }
        return total
    }

    /**
     * Turns a downloaded [part] file in [root] into a library document named after [displayName],
     * with the extension decided by the content. If a document with identical bytes is already in
     * [root] it is returned instead (no duplicate). The part file is gone afterwards in every case.
     *
     * A name is free only if no file in [root] has it (case-insensitively) and [hasLeftoverData] is
     * false for it: data kept under the name of a document deleted or renamed outside the app
     * (sidecar, rendition, unlocked copy) must not be inherited by an unrelated new document.
     */
    fun finalize(
        part: File,
        root: File,
        displayName: String?,
        hasLeftoverData: (fileName: String) -> Boolean = { false },
    ): StoredDocument {
        try {
            val type = DocumentSniffer.sniff(part) ?: throw ImportException(rejection(part, displayName))
            findIdentical(root, part, type)?.let { return StoredDocument(it, reused = true) }

            val title = LibraryFileNames.titleForImport(displayName)
            val taken = (root.list() ?: emptyArray()).mapTo(HashSet(), LibraryFileNames::nameKey)
            val isTaken = { name: String -> LibraryFileNames.nameKey(name) in taken || hasLeftoverData(name) }
            repeat(RENAME_ATTEMPTS) {
                val name = LibraryFileNames.uniqueFileName(title, type.extension, isTaken)
                val target = File(root, name)
                if (!target.exists() && part.renameTo(target)) return StoredDocument(target, reused = false)
                taken += LibraryFileNames.nameKey(name)
            }
            throw ImportException(ImportProblem.WRITE_FAILED)
        } finally {
            part.delete()
        }
    }

    /**
     * Short user-facing reason, e.g. "Lecture.pages (unsupported file type)". [remote] = the file
     * came from a network provider, so a read failure is most likely a connectivity problem.
     */
    fun failureText(displayName: String?, problem: ImportProblem, remote: Boolean): String {
        val reason = when (problem) {
            ImportProblem.UNSUPPORTED_TYPE -> "unsupported file type"
            ImportProblem.OLD_WORD_FORMAT -> "old .doc format - save it as .docx"
            ImportProblem.PROTECTED_WORD_FILE -> "password-protected Word file"
            ImportProblem.EMPTY_FILE -> "the file is empty"
            ImportProblem.NOT_CONVERTIBLE -> "can't be downloaded as PDF or Word"
            ImportProblem.READ_FAILED ->
                if (remote) "couldn't download - check your connection" else "couldn't read the file"
            ImportProblem.NO_PERMISSION -> "no permission to open it"
            ImportProblem.NO_SPACE -> "not enough storage space"
            ImportProblem.WRITE_FAILED -> "couldn't save it to the library"
            ImportProblem.LIBRARY_UNAVAILABLE -> "library folder unavailable"
            ImportProblem.UNEXPECTED -> "something went wrong"
        }
        // Without a name, say "a file" rather than inventing one ("Couldn't import a file (…)").
        return "${displayName?.let(::shortName) ?: "a file"} ($reason)"
    }

    private fun readFromSource(input: InputStream, buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (e: SecurityException) {
        throw ImportException(ImportProblem.NO_PERMISSION, e)
    } catch (e: IOException) {
        throw ImportException(ImportProblem.READ_FAILED, e)
    } catch (e: RuntimeException) {
        // Providers surface network and pipe errors as all sorts of runtime exceptions.
        throw ImportException(ImportProblem.READ_FAILED, e)
    }

    private fun writeProblem(e: IOException): ImportProblem {
        val message = e.message.orEmpty()
        return if ("ENOSPC" in message || "No space left" in message || "EDQUOT" in message) {
            ImportProblem.NO_SPACE
        } else {
            ImportProblem.WRITE_FAILED
        }
    }

    /** Why [part] isn't a document, with a more helpful reason for legacy / protected Word files. */
    private fun rejection(part: File, displayName: String?): ImportProblem {
        if (DocumentSniffer.signature(part) != ContentSignature.OLE) return ImportProblem.UNSUPPORTED_TYPE
        val name = displayName.orEmpty().trim()
        return when {
            name.endsWith(".doc", ignoreCase = true) -> ImportProblem.OLD_WORD_FORMAT
            name.endsWith(".docx", ignoreCase = true) -> ImportProblem.PROTECTED_WORD_FILE
            else -> ImportProblem.UNSUPPORTED_TYPE
        }
    }

    private fun findIdentical(root: File, part: File, type: DocType): File? {
        val size = part.length()
        return root.listFiles()?.firstOrNull { f ->
            LibraryFileNames.isLibraryDocumentName(f.name) && DocType.fromFileName(f.name) == type &&
                f.isFile && f.length() == size && FileOps.sameContent(f, part)
        }
    }

    private fun shortName(displayName: String?): String {
        val name = LibraryFileNames.sanitizeTitleOrDefault(displayName)
        if (name.length <= MAX_DISPLAY_NAME_CHARS) return name
        var cut = MAX_DISPLAY_NAME_CHARS - 1
        if (Character.isHighSurrogate(name[cut - 1])) cut--
        return name.substring(0, cut).trimEnd() + "…"
    }
}
