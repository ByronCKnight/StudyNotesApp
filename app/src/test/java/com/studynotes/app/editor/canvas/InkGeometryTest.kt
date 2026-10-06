package com.studynotes.app.editor.canvas

import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Stroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkGeometryTest {

    private fun stroke(id: String, vararg pts: Float, width: Float = 2f, group: String? = null) =
        Stroke(id, InkTool.PEN, 0xFF000000.toInt(), width, pts, groupId = group)

    private fun bounds(s: Stroke) = FloatArray(4).also { InkGeometry.bounds(s.points, it) }

    // ---- Simplification ----------------------------------------------------------------------

    @Test
    fun rdpDropsCollinearPointsAndKeepsEndpoints() {
        val line = FloatArray(22) { i -> if (i % 2 == 0) (i / 2).toFloat() else 0f }
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f), InkGeometry.simplify(line, 0.15f), 0f)
    }

    @Test
    fun rdpKeepsCorners() {
        val corner = floatArrayOf(0f, 0f, 5f, 0f, 10f, 0f, 10f, 5f, 10f, 10f)
        assertArrayEquals(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f), InkGeometry.simplify(corner, 0.15f), 0f)
    }

    @Test
    fun rdpRespectsTolerance() {
        val wobble = floatArrayOf(0f, 0f, 5f, 0.1f, 10f, 0f)
        assertEquals(4, InkGeometry.simplify(wobble, 0.15f).size)
        assertEquals(6, InkGeometry.simplify(wobble, 0.05f).size)
    }

    @Test
    fun rdpKeepsTheFarPointOfAClosedLoop() {
        val loop = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 0f)
        val out = InkGeometry.simplify(loop, 0.15f)
        assertEquals(0f, out[0], 0f)
        assertEquals(0f, out[out.size - 1], 0f)
        assertTrue(out.size >= 6)
    }

    @Test
    fun rdpHandlesTinyAndHugeInputs() {
        assertArrayEquals(floatArrayOf(3f, 4f), InkGeometry.simplify(floatArrayOf(3f, 4f), 0.15f), 0f)
        val many = FloatArray(200_000) { i -> if (i % 2 == 0) i.toFloat() else (i % 7).toFloat() }
        val out = InkGeometry.simplify(many, 0.15f)
        assertEquals(many[0], out[0], 0f)
        assertEquals(many[many.size - 1], out[out.size - 1], 0f)
    }

    @Test
    fun smoothingKeepsEndpointsAndAveragesInterior() {
        val pts = floatArrayOf(0f, 0f, 1f, 4f, 2f, 0f)
        val out = InkGeometry.smooth(pts)
        assertEquals(0f, out[0], 0f)
        assertEquals(2f, out[4], 0f)
        assertEquals(2f, out[3], 1e-6f) // (0 + 2*4 + 0) / 4
        assertEquals(1f, out[2], 1e-6f)
    }

    @Test
    fun lengthAndDots() {
        assertEquals(10f, InkGeometry.polylineLength(floatArrayOf(0f, 0f, 3f, 4f, 3f, 9f)), 1e-5f)
        assertTrue(InkGeometry.isDot(floatArrayOf(1f, 1f, 1.2f, 1.1f), 0.5f))
        assertFalse(InkGeometry.isDot(floatArrayOf(1f, 1f, 2f, 1f), 0.5f))
    }

    // ---- Hit testing -------------------------------------------------------------------------

    @Test
    fun distances() {
        val line = floatArrayOf(0f, 0f, 100f, 0f)
        assertEquals(7f, InkGeometry.distanceToPolyline(50f, 7f, line), 1e-5f)
        assertEquals(5f, InkGeometry.distanceToPolyline(-3f, 4f, line), 1e-5f)
        assertEquals(0f, InkGeometry.segmentSegmentDistance(0f, -1f, 0f, 1f, -1f, 0f, 1f, 0f), 0f)
        assertEquals(2f, InkGeometry.segmentSegmentDistance(0f, 2f, 10f, 2f, 0f, 0f, 10f, 0f), 1e-5f)
        assertEquals(5f, InkGeometry.distanceToPolyline(3f, 4f, floatArrayOf(0f, 0f)), 1e-5f)
    }

    @Test
    fun eraserHitIncludesStrokeWidth() {
        val s = stroke("a", 0f, 0f, 100f, 0f, width = 10f)
        val b = bounds(s)
        assertTrue(InkGeometry.eraserHits(s, b, 50f, 14f, 50f, 14f, 10f)) // reach = 10 + 5
        assertFalse(InkGeometry.eraserHits(s, b, 50f, 16f, 50f, 16f, 10f))
    }

    @Test
    fun eraserSegmentCatchesFastMoves() {
        val s = stroke("a", 0f, 0f, 100f, 0f)
        // Both samples far from the stroke, but the path between them crosses it.
        assertTrue(InkGeometry.eraserHits(s, bounds(s), 50f, -100f, 50f, 100f, 1f))
        assertFalse(InkGeometry.eraserHits(s, bounds(s), 150f, -100f, 150f, 100f, 1f))
    }

    @Test
    fun groupsAreErasedTogether() {
        val strokes = listOf(
            stroke("a", 0f, 0f, group = "g1"),
            stroke("b", 0f, 0f, group = "g1"),
            stroke("c", 0f, 0f),
            stroke("d", 0f, 0f, group = "g2"),
        )
        assertEquals(setOf("a", "b"), InkGeometry.expandGroups(strokes, listOf("a")))
        assertEquals(setOf("c"), InkGeometry.expandGroups(strokes, listOf("c")))
        assertEquals(setOf("a", "b", "d"), InkGeometry.expandGroups(strokes, listOf("b", "d")))
    }

    // ---- Hold to straighten ------------------------------------------------------------------

    @Test
    fun holdStraightensOnceAfterALongEnoughStroke() {
        val d = HoldDetector(radiusPx = 3f, holdMillis = 550L, minLengthPx = 24f)
        d.start(0f, 0f, 0L)
        d.move(100f, 0f, 100L) // moved: anchor resets at t=100
        d.move(101f, 1f, 400L) // jitter within radius: anchor stays
        assertFalse(d.shouldStraighten(600L, 101f))
        assertEquals(50L, d.millisUntilDue(600L))
        assertTrue(d.shouldStraighten(650L, 101f))
        assertFalse(d.shouldStraighten(2000L, 101f)) // only once
    }

    @Test
    fun holdNeedsMinimumLength() {
        val d = HoldDetector(3f, 550L, 24f)
        d.start(0f, 0f, 0L)
        d.move(10f, 0f, 10L)
        assertFalse(d.shouldStraighten(1000L, 10f))
        assertTrue(d.shouldStraighten(1000L, 30f))
    }

    @Test
    fun holdCheckDoesNotPollWhileTheStrokeIsTooShort() {
        // Regression (canvas-input-7): a resting pen on a short stroke used to re-check every 1 ms.
        val d = HoldDetector(3f, 550L, 24f)
        d.start(0f, 0f, 0L)
        d.move(10f, 0f, 10L)
        assertFalse(d.isLongEnough(10f))
        assertEquals(HoldDetector.NO_CHECK, d.nextCheckMillis(5000L, 10f))
        // Long enough: wait for the hold to complete, never less than 1 ms.
        assertTrue(d.isLongEnough(30f))
        assertEquals(60L, d.nextCheckMillis(500L, 30f))
        assertEquals(1L, d.nextCheckMillis(5000L, 30f))
        // Once fired there is nothing left to check.
        assertTrue(d.shouldStraighten(5000L, 30f))
        assertEquals(HoldDetector.NO_CHECK, d.nextCheckMillis(5001L, 30f))
    }

    // ---- Snap to text ------------------------------------------------------------------------

    @Test
    fun snapPreviewRects_coverTheBandOfEachSnappedStroke() {
        val horizontal = Stroke("h", InkTool.HIGHLIGHTER, 1, 12f, floatArrayOf(10f, 20f, 110f, 20f), flatCaps = true, groupId = "g")
        val vertical = Stroke("v", InkTool.HIGHLIGHTER, 1, 12f, floatArrayOf(50f, 110f, 50f, 10f), flatCaps = true, groupId = "g")
        val rects = InkGeometry.snapPreviewRects(listOf(horizontal, vertical))
        assertArrayEquals(
            floatArrayOf(
                10f, 14f, 110f, 26f, // along x, half the width above and below
                44f, 10f, 56f, 110f, // along y (rotated page), half the width left and right
            ),
            rects,
            0.001f,
        )
    }

    @Test
    fun snapPreviewRects_matchTheSnappedStrokes() {
        val lines = floatArrayOf(98f, 99f, 310f, 111f, 100f, 115f, 250f, 127f)
        val strokes = InkGeometry.snappedStrokes(lines, 1, "g") { "id" }
        val rects = InkGeometry.snapPreviewRects(strokes)
        assertEquals(8, rects.size)
        for (i in 0 until 2) {
            val s = strokes[i]
            assertEquals(s.points[0], rects[4 * i], 0.001f)
            assertEquals(s.points[2], rects[4 * i + 2], 0.001f)
            assertEquals(s.points[1] - s.width / 2f, rects[4 * i + 1], 0.001f)
            assertEquals(s.points[1] + s.width / 2f, rects[4 * i + 3], 0.001f)
        }
    }

    private val freehand = FloatArray(42) { i -> if (i % 2 == 0) 100f + i * 5f else 106f + (i % 4) * 0.5f }

    @Test
    fun snapAcceptsMatchingLine() {
        val rects = floatArrayOf(98f, 99f, 310f, 111f)
        assertTrue(InkGeometry.snapPlausible(rects, freehand, 14f))
    }

    @Test
    fun snapRejectsTallRects() {
        val rects = floatArrayOf(98f, 80f, 310f, 130f) // 50 pt > 3 * 14
        assertFalse(InkGeometry.snapPlausible(rects, freehand, 14f))
    }

    @Test
    fun snapRejectsSelectionsSpanningTooManyLines() {
        val rects = floatArrayOf(
            98f, 99f, 310f, 111f,
            0f, 115f, 500f, 127f,
            0f, 131f, 500f, 143f,
        )
        assertFalse(InkGeometry.snapPlausible(rects, freehand, 14f))
    }

    @Test
    fun snapRejectsRectsAwayFromTheStroke() {
        val rects = floatArrayOf(400f, 99f, 600f, 111f)
        assertFalse(InkGeometry.snapPlausible(rects, freehand, 14f))
        assertFalse(InkGeometry.snapPlausible(FloatArray(0), freehand, 14f))
    }

    @Test
    fun snappedStrokesFollowTheLines() {
        var n = 0
        val out = InkGeometry.snappedStrokes(floatArrayOf(10f, 100f, 200f, 112f, 10f, 116f, 80f, 126f), 0xFFFFE94D.toInt(), "g") {
            "s${n++}"
        }
        assertEquals(2, out.size)
        val first = out[0]
        assertEquals("s0", first.id)
        assertEquals(InkTool.HIGHLIGHTER, first.tool)
        assertTrue(first.flatCaps)
        assertEquals("g", first.groupId)
        assertEquals(12f * 1.15f, first.width, 1e-5f)
        assertArrayEquals(floatArrayOf(10f, 106f, 200f, 106f), first.points, 0f)
        assertEquals("g", out[1].groupId)
    }

    @Test
    fun pointBufferGrows() {
        val b = PointBuffer(1)
        for (i in 0 until 100) b.add(i.toFloat(), -i.toFloat())
        assertEquals(100, b.pointCount)
        assertEquals(99f, b.x(99), 0f)
        b.set(1, 5f, 6f)
        b.truncate(2)
        assertArrayEquals(floatArrayOf(0f, 0f, 5f, 6f), b.toArray(), 0f)
    }

    @Test
    fun snapOnVerticalTextRunsAlongTheLine() {
        // A word reading down the screen (page with /Rotate 90): 10 pt across, 34 pt along.
        val rects = floatArrayOf(400f, 390f, 410f, 424f)
        val down = FloatArray(20) { i -> if (i % 2 == 0) 405f + (i % 4) * 0.3f else 392f + i * 1.6f }
        var n = 0
        val out = InkGeometry.snapStrokes(rects, down, 14f, 0xFFFFE94D.toInt(), "g") { "s${n++}" }!!
        assertEquals(1, out.size)
        assertArrayEquals(floatArrayOf(405f, 390f, 405f, 424f), out[0].points, 1e-4f)
        assertEquals(10f * 1.15f, out[0].width, 1e-4f)
        assertTrue(out[0].flatCaps)
    }

    @Test
    fun snapOnHorizontalTextIsUnchanged() {
        val rects = floatArrayOf(98f, 99f, 310f, 111f)
        val out = InkGeometry.snapStrokes(rects, freehand, 14f, 0xFFFFE94D.toInt(), "g") { "s" }!!
        assertArrayEquals(floatArrayOf(98f, 105f, 310f, 105f), out[0].points, 1e-4f)
        assertFalse(InkGeometry.isVerticalText(rects, freehand))
    }

    @Test
    fun aTallSingleLetterWithAHorizontalStrokeIsNotVerticalText() {
        val letter = floatArrayOf(100f, 99f, 105f, 111f)
        assertFalse(InkGeometry.isVerticalText(letter, floatArrayOf(98f, 106f, 107f, 106f)))
    }
}
