package com.studynotes.app.library.ui

import com.studynotes.app.model.DocType
import java.util.Locale
import kotlin.math.roundToLong

/** Separator between the parts of a metadata line, e.g. "PDF · 2.4 MB". */
const val META_SEPARATOR = " · "

private val SIZE_UNITS = listOf("KB", "MB", "GB", "TB")

/** How many failed file names an import summary lists before collapsing the rest into "and N more". */
private const val MAX_LISTED_FAILURES = 2

/** Short, user-facing name of a document type, as shown on cards: "PDF" or "Word". */
val DocType.label: String
    get() = when (this) {
        DocType.PDF -> "PDF"
        DocType.DOCX -> "Word"
    }

/**
 * Compact file size using decimal (SI) units, like the system Files app: "999 B", "18 KB",
 * "2.4 MB". Values below 10 of a unit keep one decimal; larger ones are whole numbers. A value that
 * would round to 1000 moves up to the next unit ("1.0 MB" rather than "1000 KB").
 */
fun formatFileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
    if (bytes < 1000) return String.format(locale, "%d B", bytes.coerceAtLeast(0))
    var value = bytes / 1000.0
    var unit = 0
    while (value.roundToLong() >= 1000 && unit < SIZE_UNITS.lastIndex) {
        value /= 1000
        unit++
    }
    val tenths = (value * 10).roundToLong()
    return if (tenths < 100) {
        String.format(locale, "%.1f %s", tenths / 10.0, SIZE_UNITS[unit])
    } else {
        String.format(locale, "%d %s", value.roundToLong(), SIZE_UNITS[unit])
    }
}

/** "1 mark" / "12 marks" — highlights, pen strokes and notes all count as marks. */
fun marksLabel(count: Int): String = if (count == 1) "1 mark" else "$count marks"

/** The number on a card's marks badge: "12", capped at "999+" so the badge stays small. */
fun marksBadgeText(count: Int): String = if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString()

private const val MAX_BADGE_COUNT = 999

/** The secondary line under a card title: "PDF · 2.4 MB" (the mark count has its own badge). */
fun metaLine(
    type: DocType,
    sizeBytes: Long,
    locale: Locale = Locale.getDefault(),
): String = type.label + META_SEPARATOR + formatFileSize(sizeBytes, locale)

/**
 * Body of the delete confirmation. Says plainly that the file itself goes, from which folder, and
 * that it can't be undone: the shared folder is often filled from a PC, so this may be the only copy.
 */
fun deleteConfirmationText(fileName: String, folderPath: String, isPublic: Boolean): String =
    if (isPublic) {
        "“$fileName” will be permanently deleted from $folderPath on this device, along with its " +
            "highlights and notes. This can't be undone."
    } else {
        "“$fileName” will be permanently deleted from this device, along with its highlights and " +
            "notes. This can't be undone."
    }

/**
 * Shown when documents could not be moved from app storage into the shared folder (they stay in
 * app storage, unlisted, and the move is retried each time the app is reopened).
 */
fun documentsLeftBehindMessage(count: Int, folderPath: String): String {
    val documents = if (count == 1) "1 document" else "$count documents"
    val pronoun = if (count == 1) "it's" else "they're"
    return "$documents couldn't be moved to $folderPath, so $pronoun hidden for now (nothing was " +
        "deleted). Free up some space, then reopen StudyNotes."
}

/**
 * Snackbar text after an import: "Imported “Notes”", "Imported 3 documents", then documents that
 * were already there ("“Notes” is already in your library"), then the failures ("Couldn't import
 * a.jpg (not a PDF or Word file) and 2 more").
 */
fun importSummary(
    importedTitles: List<String>,
    failures: List<String>,
    alreadyTitles: List<String> = emptyList(),
): String {
    val parts = mutableListOf<String>()
    when (importedTitles.size) {
        0 -> Unit
        1 -> parts += "Imported “${importedTitles.single()}”"
        else -> parts += "Imported ${importedTitles.size} documents"
    }
    when (alreadyTitles.size) {
        0 -> Unit
        1 -> parts += "“${alreadyTitles.single()}” is already in your library"
        else -> parts += "${alreadyTitles.size} documents were already in your library"
    }
    if (failures.isNotEmpty()) {
        val listed = failures.take(MAX_LISTED_FAILURES).joinToString(", ")
        val more = failures.size - MAX_LISTED_FAILURES
        parts += "Couldn't import $listed" + if (more > 0) " and $more more" else ""
    }
    return if (parts.isEmpty()) "Nothing was imported" else parts.joinToString(". ")
}
