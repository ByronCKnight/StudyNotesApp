package com.studynotes.app.editor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchBarTest {

    @Test
    fun blankQuery_showsNothing() {
        assertNull(searchCountText("", 0, -1, false))
        assertNull(searchCountText("  ", 3, 0, true))
    }

    @Test
    fun hits_showPositionOfCount() {
        assertEquals("1 of 12", searchCountText("cell", 12, 0, false))
        assertEquals("12 of 12", searchCountText("cell", 12, 11, true))
        // Never "0 of n", even before the first hit is chosen.
        assertEquals("1 of 3", searchCountText("cell", 3, -1, true))
    }

    @Test
    fun noHits_saysSoOnlyOnceTheSearchIsDone() {
        assertNull(searchCountText("cell", 0, -1, true))
        assertEquals("No matches", searchCountText("cell", 0, -1, false))
    }
}
