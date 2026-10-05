package com.studynotes.app.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderBudgetsTest {

    // ---- Tile budget (rendering-perf-3) --------------------------------------------------------

    @Test
    fun budgetIsPerViewport() {
        val b = TileBudget()
        b.beginPass(10L)
        repeat(3) { b.onTileRendered() }
        assertTrue(b.isExhausted(3))
        b.beginPass(10L) // same viewport: still exhausted
        assertTrue(b.isExhausted(3))
        b.beginPass(11L) // the viewport changed: fresh budget
        assertFalse(b.isExhausted(3))
    }

    @Test
    fun droppingTilesWithoutAViewportChangeRestoresTheBudget() {
        // Regression: after trimMemory (app hidden and shown) the same viewport could not re-render.
        val b = TileBudget()
        b.beginPass(42L)
        repeat(84) { b.onTileRendered() }
        assertTrue(b.isExhausted(84))
        b.reset()
        b.beginPass(42L)
        assertFalse(b.isExhausted(84))
    }

    // ---- Base bitmaps that do not fit together (rendering-perf-7) ------------------------------

    @Test
    fun visiblePagesThatFitKeepFullResolution() {
        assertEquals(1f, BaseBudget.visibleScale(40L, 100L), 0f)
        assertEquals(1f, BaseBudget.visibleScale(90L, 100L), 0f)
        assertEquals(1f, BaseBudget.visibleScale(0L, 100L), 0f)
    }

    @Test
    fun visiblePagesThatDoNotFitAreScaledIntoTheBudget() {
        val capacity = 51L * 1024 * 1024
        val visible = 4L * 16 * 1024 * 1024 // four 4 MP pages
        val s = BaseBudget.visibleScale(visible, capacity)
        assertTrue(s < 1f)
        // Bytes scale with the area (scale squared): the scaled set fits the visible share.
        assertTrue(visible * s * s <= capacity * BaseBudget.VISIBLE_SHARE * 1.0001)
        assertTrue(visible * s * s > capacity * BaseBudget.VISIBLE_SHARE * 0.99)
    }

    @Test
    fun scaleIsBoundedBelow() {
        assertEquals(BaseBudget.MIN_SCALE, BaseBudget.visibleScale(Long.MAX_VALUE / 2, 100L), 0f)
    }
}
