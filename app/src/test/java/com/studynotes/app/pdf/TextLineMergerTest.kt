package com.studynotes.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLineMergerTest {

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = TextBox(left, top, right, bottom)

    @Test
    fun runsOnOneLineBecomeOneRect() {
        val merged = TextLineMerger.merge(
            listOf(
                box(72f, 100f, 140f, 112f),
                box(143f, 100f, 180f, 112f), // bold word
                box(183f, 99f, 300f, 113f), // larger font
            ),
        )
        assertEquals(listOf(box(72f, 99f, 300f, 113f)), merged)
    }

    @Test
    fun separateLinesStaySeparateSortedTopToBottom() {
        val line1 = box(72f, 100f, 500f, 112f)
        val line2 = box(72f, 115f, 480f, 127f)
        val line3 = box(72f, 130f, 300f, 142f)
        assertEquals(listOf(line1, line2, line3), TextLineMerger.merge(listOf(line3, line1, line2)))
    }

    @Test
    fun superscriptJoinsItsLine() {
        val merged = TextLineMerger.merge(
            listOf(
                box(72f, 100f, 200f, 112f),
                box(200f, 97f, 206f, 105f), // superscript: overlaps 5 of its 8 pt
                box(208f, 100f, 300f, 112f),
            ),
        )
        assertEquals(listOf(box(72f, 97f, 300f, 112f)), merged)
    }

    @Test
    fun halfOfTheSmallerHeightIsTheThreshold() {
        val line = box(0f, 100f, 100f, 110f)
        // Overlap exactly 5 of 10 pt: same line.
        assertEquals(1, TextLineMerger.merge(listOf(line, box(120f, 105f, 200f, 115f))).size)
        // Overlap 4.9 pt: different lines.
        assertEquals(2, TextLineMerger.merge(listOf(line, box(120f, 105.1f, 200f, 115.1f))).size)
        // Adjacent lines that only touch never merge.
        assertEquals(2, TextLineMerger.merge(listOf(line, box(0f, 110f, 100f, 120f))).size)
    }

    @Test
    fun rectsOnTheSameLineInAnyOrderMerge() {
        val merged = TextLineMerger.merge(
            listOf(
                box(300f, 200f, 400f, 212f),
                box(72f, 100f, 400f, 112f),
                box(72f, 200f, 150f, 212f),
                box(160f, 200f, 290f, 212f),
            ),
        )
        assertEquals(listOf(box(72f, 100f, 400f, 112f), box(72f, 200f, 400f, 212f)), merged)
    }

    @Test
    fun emptyAndNonFiniteRectsAreDropped() {
        val good = box(72f, 100f, 200f, 112f)
        val merged = TextLineMerger.merge(
            listOf(
                box(Float.NaN, 100f, 200f, 112f),
                box(72f, 100f, Float.POSITIVE_INFINITY, 112f),
                box(80f, 100f, 80f, 112f), // zero width
                box(80f, 100f, 90f, 100f), // zero height
                box(90f, 112f, 80f, 100f), // inverted
                good,
            ),
        )
        assertEquals(listOf(good), merged)
    }

    @Test
    fun nothingValidGivesAnEmptyList() {
        assertTrue(TextLineMerger.merge(emptyList()).isEmpty())
        assertTrue(TextLineMerger.merge(listOf(box(Float.NaN, 0f, 1f, 1f), box(5f, 5f, 5f, 5f))).isEmpty())
    }

    @Test
    fun columnsAtTheSameHeightShareALine() {
        // Two rects on the same baseline belong to one visual line, whatever the gap between them.
        val merged = TextLineMerger.merge(listOf(box(72f, 100f, 280f, 112f), box(320f, 100f, 540f, 112f)))
        assertEquals(listOf(box(72f, 100f, 540f, 112f)), merged)
    }
}
