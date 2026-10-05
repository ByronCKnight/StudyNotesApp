package com.studynotes.app.library.data

import com.studynotes.app.model.DocType
import java.util.Locale

/**
 * Pure naming rules for files in the library folder: sanitising titles that come from content
 * providers or the rename dialog, picking free "Name (2).pdf" style names, and recognising which
 * entries of the folder are documents.
 */
object LibraryFileNames {
    /** Longest title kept, in UTF-16 chars (a surrogate pair is never split). */
    const val MAX_TITLE_CHARS = 120

    /**
     * Longest title kept, in UTF-8 bytes. File names are limited to 255 bytes and the library adds
     * suffixes such as " (12).docx" and ".json.bak" (sidecars), so CJK or emoji titles that are
     * within [MAX_TITLE_CHARS] can still be too long without this second limit.
     */
    const val MAX_TITLE_BYTES = 200

    /** Used when a name has nothing usable left after sanitising. */
    const val DEFAULT_TITLE = "Document"

    /** Suffix of the hidden temp file an import downloads into. */
    const val PART_SUFFIX = ".part"

    private const val ILLEGAL_CHARS = "\\/:*?\"<>|"
    private val WHITESPACE_RUN = Regex("\\s+")
    private val COUNTER_SUFFIX = Regex("""^(.*\S) \((\d{1,6})\)$""")

    /** Extensions dropped from an incoming name before the content-derived one is appended. */
    private val REPLACEABLE_EXTENSIONS = DocType.entries.map { it.extension }.toSet() + "doc"

    /**
     * Cleans [raw] for use as a file title: removes `\ / : * ? " < > |` and unpaired surrogates,
     * turns control characters into spaces, collapses whitespace, trims spaces and dots from both
     * ends (a leading dot would hide the file) and limits the length. Returns null when nothing
     * usable is left.
     */
    fun sanitizeTitle(raw: String): String? {
        val sb = StringBuilder(raw.length)
        var i = 0
        while (i < raw.length) {
            val cp = raw.codePointAt(i)
            val count = Character.charCount(cp)
            when {
                cp in 0xD800..0xDFFF -> Unit // unpaired surrogate half
                cp < 0x10000 && cp.toChar() in ILLEGAL_CHARS -> Unit
                Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029 -> sb.append(' ')
                else -> sb.appendCodePoint(cp)
            }
            i += count
        }
        val collapsed = sb.toString().replace(WHITESPACE_RUN, " ").trim(::isTrimmed)
        return truncate(collapsed).trim(::isTrimmed).ifEmpty { null }
    }

    /** The characters of [raw] that can't be part of a file name, in order of appearance. */
    fun illegalCharsIn(raw: String): String = raw.filter { it in ILLEGAL_CHARS }.toSet().joinToString("")

    /** [sanitizeTitle], falling back to [DEFAULT_TITLE]. */
    fun sanitizeTitleOrDefault(raw: String?): String = raw?.let(::sanitizeTitle) ?: DEFAULT_TITLE

    /**
     * Title for an incoming file called [displayName]: a trailing .pdf/.docx/.doc (any case) is
     * dropped because the extension is re-derived from the content; any other dotted part
     * ("Notes v2.1") is kept as part of the title.
     */
    fun titleForImport(displayName: String?): String {
        val name = displayName.orEmpty().trim()
        val ext = name.substringAfterLast('.', "")
        val stem = if (ext.lowercase(Locale.ROOT) in REPLACEABLE_EXTENSIONS) name.dropLast(ext.length + 1) else name
        return sanitizeTitleOrDefault(stem)
    }

    /**
     * The first of "title.ext", "title (2).ext", "title (3).ext", ... for which [isTaken] is false.
     * A title that already ends in a counter continues from it ("Lecture (2)" -> "Lecture (3).pdf")
     * instead of stacking counters. Callers compare case-insensitively (see [nameKey]) because
     * Android's shared storage is case-insensitive.
     */
    fun uniqueFileName(title: String, extension: String, isTaken: (String) -> Boolean): String {
        val first = "$title.$extension"
        if (!isTaken(first)) return first
        val match = COUNTER_SUFFIX.matchEntire(title)
        val stem = match?.groupValues?.get(1) ?: title
        var n = match?.groupValues?.get(2)?.toInt()?.plus(1)?.coerceAtLeast(2) ?: 2
        while (true) {
            val candidate = "$stem ($n).$extension"
            if (!isTaken(candidate)) return candidate
            n++
        }
    }

    /** Case-insensitive identity of a file name, for clash checks. */
    fun nameKey(fileName: String): String = fileName.lowercase(Locale.ROOT)

    /** True for the entries the library lists: visible files named *.pdf or *.docx. */
    fun isLibraryDocumentName(fileName: String): Boolean =
        !fileName.startsWith(".") && !fileName.endsWith(PART_SUFFIX, ignoreCase = true) &&
            DocType.fromFileName(fileName) != null

    /** Hidden temp file name an import of [displayName] downloads into, e.g. ".Lecture.pdf.part". */
    fun partFileName(displayName: String?): String = "." + sanitizeTitleOrDefault(displayName) + PART_SUFFIX

    private fun isTrimmed(c: Char) = c == '.' || c.isWhitespace()

    /** Cuts [s] to [MAX_TITLE_CHARS] chars and [MAX_TITLE_BYTES] UTF-8 bytes at a code point boundary. */
    private fun truncate(s: String): String {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val count = Character.charCount(cp)
            val cpBytes = when {
                cp < 0x80 -> 1
                cp < 0x800 -> 2
                cp < 0x10000 -> 3
                else -> 4
            }
            if (i + count > MAX_TITLE_CHARS || bytes + cpBytes > MAX_TITLE_BYTES) break
            bytes += cpBytes
            i += count
        }
        return s.substring(0, i)
    }
}
