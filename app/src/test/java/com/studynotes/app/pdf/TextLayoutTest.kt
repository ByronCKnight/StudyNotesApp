package com.studynotes.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextLayoutTest {

    // ---- Builder ----

    @Test
    fun lineRanges_splitAtAnyLineBreak_andSkipBlankLines() {
        val text = "one two\r\nthree\n\n  \rfour"
        val lines = TextLayoutBuilder.lineRanges(text).map { text.substring(it) }
        assertEquals(listOf("one two", "three", "four"), lines)
    }

    @Test
    fun wordRanges_areRunsOfNonWhitespace() {
        val text = "  #Service Fees #Int Pymt Fee-133.21  "
        val words = TextLayoutBuilder.wordRanges(text, text.indices).map { text.substring(it) }
        assertEquals(listOf("#Service", "Fees", "#Int", "Pymt", "Fee-133.21"), words)
        assertTrue(TextLayoutBuilder.wordRanges("   ", 0..2).isEmpty())
    }

    @Test
    fun linesOf_groupsWordsByVerticalOverlap_andSortsLeftToRight() {
        val words = listOf(
            word(100f, 100f, 140f, 112f),
            word(50f, 100f, 90f, 112f), // same line, further left
            word(60f, 96f, 70f, 104f), // a superscript overlapping the line: same line
            word(50f, 120f, 200f, 132f), // next line, which the engine did not split off
        )
        val lines = TextLayoutBuilder.linesOf(words, vertical = false)
        assertEquals(2, lines.size)
        assertEquals(listOf(50f, 60f, 100f), lines[0].words.map { it.box.left })
        assertEquals(TextBox(50f, 96f, 140f, 112f), lines[0].box)
        assertEquals(1, lines[1].words.size)
    }

    // ---- Selection ----

    private val layout = PageTextLayout(
        listOf(
            line(100f, 112f, "Chapter" to (56f to 130f), "1:" to (135f to 150f), "Photosynthesis" to (156f to 291f)),
            line(130f, 142f, "Plants" to (56f to 90f), "make" to (95f to 125f), "sugar" to (130f to 160f)),
            line(146f, 158f, "from" to (56f to 80f), "light" to (85f to 110f)),
        ),
    )

    @Test
    fun oneLine_selectsTheWordsTheSpanTouches() {
        val s = layout.select(62f, 106f, 140f, 106f)!!
        assertEquals(listOf(TextBox(56f, 100f, 150f, 112f)), s.boxes)
        assertEquals(0, s.endLine)
        // The same span backwards is the same selection.
        assertEquals(s.boxes, layout.select(140f, 106f, 62f, 106f)!!.boxes)
    }

    @Test
    fun aTinyDragInsideAWord_selectsThatWholeWord() {
        val s = layout.select(80f, 104f, 82f, 105f)!!
        assertEquals(listOf(TextBox(56f, 100f, 130f, 112f)), s.boxes)
    }

    @Test
    fun aDragInTheGapBetweenWords_selectsNothing() {
        val s = layout.select(131f, 106f, 134f, 106f)!!
        assertTrue(s.boxes.isEmpty())
    }

    @Test
    fun acrossLines_restOfFirst_wholeMiddle_startOfLast() {
        val s = layout.select(140f, 106f, 70f, 152f)!!
        assertEquals(
            listOf(
                TextBox(135f, 100f, 291f, 112f), // "1: Photosynthesis"
                TextBox(56f, 130f, 160f, 142f), // whole line
                TextBox(56f, 146f, 80f, 158f), // "from"
            ),
            s.boxes,
        )
        assertEquals(2, s.endLine)
    }

    @Test
    fun draggingUpwards_isReadInReadingOrder() {
        val s = layout.select(70f, 152f, 140f, 106f)!!
        assertEquals(TextBox(135f, 100f, 291f, 112f), s.boxes.first())
        assertEquals(TextBox(56f, 146f, 80f, 158f), s.boxes.last())
        assertEquals(0, s.endLine)
    }

    @Test
    fun startAwayFromAnyLine_selectsNothing() {
        assertNull(layout.select(100f, 30f, 200f, 30f)) // far above the title
        assertNull(layout.select(100f, 200f, 200f, 200f)) // below the last line
        // Just outside a line's band still counts as on it.
        assertEquals(1, layout.select(100f, 118f, 150f, 118f)!!.boxes.size)
    }

    @Test
    fun endInTheMargin_extendsToTheNearestLine() {
        // Down past the last line, into the bottom margin: everything to the end.
        val s = layout.select(62f, 106f, 400f, 170f)!!
        assertEquals(3, s.boxes.size)
        assertEquals(TextBox(56f, 146f, 110f, 158f), s.boxes.last())
        // Into the left margin of the third line: the end line contributes nothing.
        val margin = layout.select(62f, 106f, 10f, 152f)!!
        assertEquals(2, margin.boxes.size)
        assertEquals(2, margin.endLine)
    }

    @Test
    fun theEndLineIsSticky_untilThePenClearlyLeavesIt() {
        // Ending on line 1 ...
        val onLine1 = layout.select(62f, 106f, 100f, 136f)!!
        assertEquals(1, onLine1.endLine)
        // ... then wobbling 3 pt below its band, which is already nearer to line 2's band: still line 1.
        val wobble = layout.select(62f, 106f, 100f, 145f, previousEndLine = 1)!!
        assertEquals(1, wobble.endLine)
        assertEquals(2, wobble.boxes.size)
        // Well inside line 2's band: the end moves.
        val moved = layout.select(62f, 106f, 100f, 152f, previousEndLine = 1)!!
        assertEquals(2, moved.endLine)
        // Without the memory, 145 goes to the nearest line, which is line 2.
        assertEquals(2, layout.select(62f, 106f, 100f, 145f)!!.endLine)
    }

    @Test
    fun verticalText_isHandledWithAxesSwapped() {
        // A rotated page: lines read down the screen at x = 770 and 790; words stacked vertically.
        val vertical = PageTextLayout(
            listOf(
                TextLine(TextBox(766f, 56f, 786f, 330f), listOf(TextWord(TextBox(766f, 56f, 786f, 150f), 0, 7), TextWord(TextBox(766f, 160f, 786f, 330f), 8, 22))),
                TextLine(TextBox(740f, 56f, 752f, 200f), listOf(TextWord(TextBox(740f, 56f, 752f, 200f), 23, 30))),
            ),
        )
        assertTrue(vertical.isVertical)
        assertFalse(layout.isVertical)
        val s = vertical.select(776f, 60f, 776f, 250f)!!
        assertEquals(listOf(TextBox(766f, 56f, 786f, 330f)), s.boxes)
        val oneWord = vertical.select(776f, 60f, 776f, 100f)!!
        assertEquals(listOf(TextBox(766f, 56f, 786f, 150f)), oneWord.boxes)
    }

    @Test
    fun emptyLayout_selectsNothing() {
        assertNull(PageTextLayout(emptyList()).select(10f, 10f, 20f, 20f))
    }

    private fun word(l: Float, t: Float, r: Float, b: Float) = TextWord(TextBox(l, t, r, b), 0, 1)

    private fun line(top: Float, bottom: Float, vararg words: Pair<String, Pair<Float, Float>>): TextLine {
        var at = 0
        val list = words.map { (text, x) ->
            TextWord(TextBox(x.first, top, x.second, bottom), at, at + text.length).also { at += text.length + 1 }
        }
        return TextLine(list.map { it.box }.reduce(TextBox::union), list)
    }
}
