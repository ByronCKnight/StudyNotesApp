package com.studynotes.app.editor.ui

import com.studynotes.app.model.Palette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorContrastTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    // Card colours of the tool options popover (Theme.kt), light and dark.
    private val lightCard = 0xFFE7E7EE.toInt()
    private val darkCard = 0xFF292A2F.toInt()
    private val fallback = 0xFFC6C5D0.toInt()

    @Test
    fun extremesAndIdentity() {
        assertEquals(21.0, contrastRatio(black, white), 0.01)
        assertEquals(1.0, contrastRatio(darkCard, darkCard), 0.0001)
        assertEquals(contrastRatio(white, darkCard), contrastRatio(darkCard, white), 0.0001)
    }

    @Test
    fun blackPenInkFallsBackOnTheDarkCard() {
        val ink = Palette.PEN_COLORS[0]
        assertEquals(fallback, readableOn(ink, darkCard, fallback))
    }

    @Test
    fun blackPenInkIsKeptOnTheLightCard() {
        val ink = Palette.PEN_COLORS[0]
        assertEquals(ink, readableOn(ink, lightCard, fallback))
    }

    @Test
    fun lightHighlighterColoursStayOnTheDarkCard() {
        for (color in Palette.HIGHLIGHTER_COLORS) {
            assertEquals(color, readableOn(color, darkCard, fallback))
        }
    }

    @Test
    fun whateverIsChosenIsVisible() {
        for (card in listOf(lightCard, darkCard)) {
            for (ink in Palette.PEN_COLORS + Palette.HIGHLIGHTER_COLORS) {
                val chosen = readableOn(ink, card, if (card == darkCard) fallback else 0xFF45464F.toInt())
                assertTrue("ink ${Integer.toHexString(ink)} on ${Integer.toHexString(card)}", contrastRatio(chosen, card) >= MIN_UI_CONTRAST)
            }
        }
    }
}
