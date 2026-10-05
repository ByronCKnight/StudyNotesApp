package com.studynotes.app.editor

import com.studynotes.app.editor.ui.pageLabel
import com.studynotes.app.editor.ui.parsePageInput
import com.studynotes.app.editor.ui.relativeAge
import com.studynotes.app.editor.ui.saveStatusText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EditorHelpersTest {

    private val now = 1_700_000_000_000L
    private val minute = 60_000L

    @Test
    fun savedStatusText() {
        assertEquals("Saved · just now", saveStatusText(SaveStatus.Saved(now - 5_000L), now))
        assertEquals("Saved · just now", saveStatusText(SaveStatus.Saved(now + 5_000L), now))
        assertEquals("Saved · 1 min ago", saveStatusText(SaveStatus.Saved(now - minute), now))
        assertEquals("Saved · 59 min ago", saveStatusText(SaveStatus.Saved(now - 59 * minute - 30_000L), now))
        assertEquals("Saved · 1 h ago", saveStatusText(SaveStatus.Saved(now - 60 * minute), now))
        assertEquals("Saved · 2 h ago", saveStatusText(SaveStatus.Saved(now - 150 * minute), now))
        assertEquals("Saved · 3 d ago", saveStatusText(SaveStatus.Saved(now - 3 * 24 * 60 * minute), now))
    }

    @Test
    fun otherStatusTexts() {
        assertNull("never saved -> caller shows document info", saveStatusText(SaveStatus.Saved(null), now))
        assertEquals("Unsaved changes", saveStatusText(SaveStatus.Unsaved, now))
        assertEquals("Saving…", saveStatusText(SaveStatus.Saving, now))
        assertEquals("Save failed", saveStatusText(SaveStatus.Failed, now))
    }

    @Test
    fun relativeAgeBoundaries() {
        assertEquals("just now", relativeAge(0L))
        assertEquals("just now", relativeAge(minute - 1))
        assertEquals("1 min ago", relativeAge(minute))
        assertEquals("23 h ago", relativeAge(24 * 60 * minute - 1))
        assertEquals("1 d ago", relativeAge(24 * 60 * minute))
    }

    @Test
    fun pageInputParsing() {
        assertEquals(0, parsePageInput("1", 48))
        assertEquals(47, parsePageInput("48", 48))
        assertEquals(11, parsePageInput("  12 ", 48))
        assertEquals(6, parsePageInput("007", 48))
        assertNull(parsePageInput("", 48))
        assertNull(parsePageInput("   ", 48))
        assertNull(parsePageInput("0", 48))
        assertNull(parsePageInput("49", 48))
        assertNull(parsePageInput("-3", 48))
        assertNull(parsePageInput("1.5", 48))
        assertNull(parsePageInput("12a", 48))
        assertNull(parsePageInput("99999999999999999999", 48))
        assertNull("empty document", parsePageInput("1", 0))
    }

    @Test
    fun pageLabelIsOneBased() {
        assertEquals("12 / 48", pageLabel(11, 48))
        assertEquals("1 / 1", pageLabel(0, 1))
    }

    @Test
    fun notePositionIsClampedOntoThePage() {
        // Fits: unchanged.
        assertEquals(40f to 60f, clampNotePosition(40f, 60f, 100f, 30f, 612f, 792f))
        // Too far right / bottom: pulled back so the whole card is on the page.
        assertEquals(512f to 762f, clampNotePosition(600f, 790f, 100f, 30f, 612f, 792f))
        // Negative coordinates.
        assertEquals(0f to 0f, clampNotePosition(-5f, -1f, 100f, 30f, 612f, 792f))
        // Card larger than the page: pinned to the top-left corner.
        assertEquals(0f to 0f, clampNotePosition(20f, 20f, 150f, 300f, 100f, 200f))
    }
}
