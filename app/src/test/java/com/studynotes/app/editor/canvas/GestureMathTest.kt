package com.studynotes.app.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureMathTest {

    // ---- Second finger while drawing with a finger (canvas-input-4) ----------------------------

    @Test
    fun secondFingerEarlyOrBeforeTheStrokeIsEstablishedMeansPanZoom() {
        val slop = 8f
        assertTrue(GesturePolicy.secondFingerCancelsStroke(100L, 200f, slop)) // within 150 ms
        assertTrue(GesturePolicy.secondFingerCancelsStroke(150L, 200f, slop))
        assertTrue(GesturePolicy.secondFingerCancelsStroke(1000L, 20f, slop)) // < 3 x slop travelled
    }

    @Test
    fun secondFingerDuringAnEstablishedStrokeIsIgnored() {
        // A 1.5 s highlight across three lines must survive a thumb brushing the screen.
        assertFalse(GesturePolicy.secondFingerCancelsStroke(1500L, 600f, 8f))
        assertFalse(GesturePolicy.secondFingerCancelsStroke(151L, 24f, 8f))
    }

    // ---- Pan remainder (canvas-input-12) -------------------------------------------------------

    @Test
    fun slowPansStillMoveWhereFloatSpacingIsTwoPixels() {
        val start = 16_777_216f // 2^24: Float spacing is 2 px from here on
        assertEquals(start, start + 0.5f, 0f) // the bug: a plain Float add loses the delta
        val r = PanRemainder()
        var y = start
        repeat(8) { y = r.next(y, 0.5f, 0f, 1e9f) }
        assertEquals(start + 4f, y, 0f)
    }

    @Test
    fun remainderIsDroppedAtTheBounds() {
        val r = PanRemainder()
        assertEquals(100f, r.next(99.5f, 3f, 0f, 100f), 0f)
        // Nothing was carried, so moving back starts from the bound.
        assertEquals(99f, r.next(100f, -1f, 0f, 100f), 0f)
    }

    @Test
    fun ordinaryPansAreExactAndResetClearsTheCarry() {
        val r = PanRemainder()
        assertEquals(12.25f, r.next(10f, 2.25f, 0f, 1000f), 0f)
        assertEquals(10f, r.next(12.25f, -2.25f, 0f, 1000f), 0f)
        r.reset()
        // Centred content: min == max pins the offset.
        assertEquals(-50f, r.next(-50f, 30f, -50f, -50f), 0f)
    }
}
