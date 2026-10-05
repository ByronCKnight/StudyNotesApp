package com.studynotes.app.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class HighlightColorTest {

    /** What a viewer shows for the compensated colour drawn at [HighlightColor.ALPHA] over white. */
    private fun onWhite(compensated: Float) = (1f - HighlightColor.ALPHA) + HighlightColor.ALPHA * compensated

    @Test
    fun whiteStaysWhite() {
        assertEquals(1f, HighlightColor.compensate(1f), EPS)
    }

    @Test
    fun formulaMatchesSpec() {
        assertEquals(0f, HighlightColor.compensate(0.45f), EPS)
        assertEquals(0.5f, HighlightColor.compensate(0.725f), EPS)
        assertEquals((0.8f - 0.45f) / 0.55f, HighlightColor.compensate(0.8f), EPS)
    }

    @Test
    fun darkChannelsAreClampedToZero() {
        assertEquals(0f, HighlightColor.compensate(0.3f), 0f)
        assertEquals(0f, HighlightColor.compensate(0f), 0f)
    }

    @Test
    fun outOfRangeInputIsClampedToOne() {
        assertEquals(1f, HighlightColor.compensate(1.5f), 0f)
    }

    @Test
    fun reachableColoursReproduceExactlyOnWhite() {
        var c = 0.45f
        while (c <= 1f) {
            assertEquals("channel $c", c, onWhite(HighlightColor.compensate(c)), EPS)
            c += 0.05f
        }
    }

    @Test
    fun paletteYellowIsCompensatedPerChannel() {
        // 0xFFFFE94D: r = 255, g = 233, b = 77 (77/255 = 0.30 < 0.45, so blue clamps to 0).
        val rgb = HighlightColor.compensatedRgb(0xFFFFE94D.toInt())
        assertArrayEquals(floatArrayOf(1f, (233f / 255f - 0.45f) / 0.55f, 0f), rgb, EPS)
    }

    @Test
    fun rgbComponentsIgnoreAlphaAndScaleToUnitRange() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), rgbComponents(0xFFFF0000.toInt()), EPS)
        assertArrayEquals(floatArrayOf(0f, 128f / 255f, 1f), rgbComponents(0x000080FF), EPS)
        assertArrayEquals(
            floatArrayOf(0x1F / 255f, 0x1F / 255f, 0x24 / 255f),
            rgbComponents(0xFF1F1F24.toInt()),
            EPS,
        )
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
