package com.studynotes.app.editor.ui

import com.studynotes.app.editor.SaveStatus

/**
 * User-facing text for the editor's save status line, e.g. "Saved · 2 min ago".
 * Returns null for a document that has never been saved (the caller shows document info instead).
 * [now] is passed in so the text can be refreshed on a timer and unit-tested.
 */
fun saveStatusText(status: SaveStatus, now: Long): String? = when (status) {
    is SaveStatus.Saved -> status.at?.let { "Saved · " + relativeAge(now - it) }
    SaveStatus.Unsaved -> "Unsaved changes"
    SaveStatus.Saving -> "Saving…"
    SaveStatus.Failed -> "Save failed"
}

/** "just now", "5 min ago", "3 h ago", "2 d ago". Negative ages (clock changes) count as "just now". */
fun relativeAge(ageMillis: Long): String {
    val minutes = ageMillis / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} d ago"
    }
}

/**
 * Parses the "Go to page" field: a 1-based page number. Returns the 0-based page index, or null when
 * the text is not a whole number between 1 and [pageCount].
 */
fun parsePageInput(text: String, pageCount: Int): Int? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length > 9 || !trimmed.all { it in '0'..'9' }) return null
    val number = trimmed.toInt()
    return if (number in 1..pageCount) number - 1 else null
}

/** Page chip label, e.g. "12 / 48" for page index 11 of 48. */
fun pageLabel(pageIndex: Int, pageCount: Int): String = "${pageIndex + 1} / $pageCount"
