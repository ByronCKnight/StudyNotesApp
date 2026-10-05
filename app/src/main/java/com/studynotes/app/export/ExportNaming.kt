package com.studynotes.app.export

import java.security.MessageDigest

/**
 * File names for exported PDFs: "<title> (annotated).pdf", safe on Android, Windows and FAT/exFAT
 * (the file may end up on a PC or SD card via Drive/OneDrive/USB) and short enough that the name
 * plus a ".part" suffix stays far below the 255-byte file-name limit.
 */
internal object ExportNaming {
    const val SUFFIX = " (annotated).pdf"
    const val FALLBACK_TITLE = "Document"
    const val MAX_TITLE_CHARS = 100
    const val MAX_TITLE_BYTES = 180

    /** Hex characters of [documentKey]: 64 bits, so two library documents never share a key. */
    const val DOCUMENT_KEY_CHARS = 16

    /** Characters Windows and FAT refuse in file names ('/' is also Android's separator). */
    private const val FORBIDDEN = "/\\:*?\"<>|"
    private val SPACE_RUNS = Regex(" {2,}")
    private const val HEX = "0123456789abcdef"

    /** "<safe title> (annotated).pdf" for a document titled [title]. */
    fun annotatedPdfName(title: String): String = safeTitle(title) + SUFFIX

    /** "<safe title>.pdf": a document exported without any marks (a Word file shared as PDF). */
    fun plainPdfName(title: String): String = safeTitle(title) + ".pdf"

    /**
     * Name of the per-document folder (inside the exports folder) for library document
     * [docFileName]: a short SHA-1 of the full file name. Library names are unique only with their
     * extension ("Lecture 3.pdf" and "Lecture 3.docx" can coexist) and sanitising can merge
     * titles ("A:B" and "A_B"), so a shared folder would let one document's export replace the bytes
     * behind a URI that was already shared for another.
     */
    fun documentKey(docFileName: String): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(docFileName.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            for (byte in digest) {
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }.take(DOCUMENT_KEY_CHARS)
    }

    /**
     * [title] with whitespace normalised to single spaces, control and forbidden characters replaced
     * by '_', leading dots removed (no hidden files), trailing dots/spaces removed (Windows drops
     * them), and cut to [MAX_TITLE_CHARS] chars / [MAX_TITLE_BYTES] UTF-8 bytes without splitting
     * a surrogate pair. Falls back to [FALLBACK_TITLE] when nothing is left.
     */
    fun safeTitle(title: String): String {
        val mapped = buildString(title.length) {
            for (ch in title) {
                append(
                    when {
                        ch.isWhitespace() -> ' '
                        ch.isISOControl() || ch in FORBIDDEN -> '_'
                        else -> ch
                    },
                )
            }
        }
        val cleaned = mapped.replace(SPACE_RUNS, " ").trimStart(' ', '.')
        return truncate(cleaned).trimEnd(' ', '.').ifEmpty { FALLBACK_TITLE }
    }

    private fun truncate(text: String): String {
        var end = 0
        var bytes = 0
        while (end < text.length) {
            val codePoint = text.codePointAt(end)
            val chars = Character.charCount(codePoint)
            val size = utf8Size(codePoint)
            if (end + chars > MAX_TITLE_CHARS || bytes + size > MAX_TITLE_BYTES) break
            end += chars
            bytes += size
        }
        return text.substring(0, end)
    }

    private fun utf8Size(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
