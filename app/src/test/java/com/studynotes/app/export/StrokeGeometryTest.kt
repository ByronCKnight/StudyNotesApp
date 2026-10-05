package com.studynotes.app.export

import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Stroke
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StrokeGeometryTest {

    /** Records path operators as (name, coordinates). */
    private class Recorder : PathSink {
        val ops = mutableListOf<Pair<String, FloatArray>>()
        override fun moveTo(x: Float, y: Float) { ops += "m" to floatArrayOf(x, y) }
        override fun lineTo(x: Float, y: Float) { ops += "l" to floatArrayOf(x, y) }
        override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            ops += "c" to floatArrayOf(x1, y1, x2, y2, x3, y3)
        }
        override fun closePath() { ops += "h" to floatArrayOf() }
        val names get() = ops.joinToString("") { it.first }
    }

    private fun stroke(vararg points: Float, width: Float = 2f, flatCaps: Boolean = false) =
        Stroke("s", InkTool.PEN, 0xFF000000.toInt(), width, points, flatCaps = flatCaps)

    @Test
    fun twoPointsAreAStraightLine() {
        val r = Recorder()
        StrokeGeometry.smoothPath(floatArrayOf(1f, 2f, 30f, 40f), r)
        assertEquals("ml", r.names)
        assertArrayEquals(floatArrayOf(1f, 2f), r.ops[0].second, EPS)
        assertArrayEquals(floatArrayOf(30f, 40f), r.ops[1].second, EPS)
    }

    @Test
    fun interiorPointsBecomeCurvesThroughMidpoints() {
        val r = Recorder()
        StrokeGeometry.smoothPath(floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 20f, 10f), r)
        assertEquals("mccl", r.names)
        // First curve: from p0 (0,0), control p1 (10,0), to mid(p1,p2) = (10,5).
        assertArrayEquals(
            floatArrayOf(20f / 3f, 0f, 10f, 5f + 2f / 3f * (0f - 5f), 10f, 5f),
            r.ops[1].second,
            EPS,
        )
        // Second curve starts where the first ended and ends at mid(p2,p3) = (15,10).
        assertArrayEquals(floatArrayOf(15f, 10f), r.ops[2].second.copyOfRange(4, 6), EPS)
        assertArrayEquals(floatArrayOf(20f, 10f), r.ops[3].second, EPS)
    }

    @Test
    fun cubicConversionIsExactlyTheQuadratic() {
        val r = Recorder()
        val p0 = floatArrayOf(3f, 7f)
        val q = floatArrayOf(40f, -12f)
        val p2 = floatArrayOf(25f, 30f)
        StrokeGeometry.quadTo(p0[0], p0[1], q[0], q[1], p2[0], p2[1], r)
        val c = r.ops.single().second
        for (step in 0..10) {
            val t = step / 10f
            val u = 1 - t
            for (axis in 0..1) {
                val quad = u * u * p0[axis] + 2 * u * t * q[axis] + t * t * p2[axis]
                val cubic = u * u * u * p0[axis] + 3 * u * u * t * c[axis] + 3 * u * t * t * c[2 + axis] +
                    t * t * t * c[4 + axis]
                assertEquals("t=$t axis=$axis", quad, cubic, 1e-3f)
            }
        }
    }

    @Test
    fun circleIsClosedAndPassesThroughItsExtremes() {
        val r = Recorder()
        StrokeGeometry.circle(50f, 60f, 5f, r)
        assertEquals("mcccch", r.names)
        assertArrayEquals(floatArrayOf(55f, 60f), r.ops[0].second, EPS)
        val ends = r.ops.subList(1, 5).map { it.second.copyOfRange(4, 6).toList() }
        assertEquals(listOf(listOf(50f, 65f), listOf(45f, 60f), listOf(50f, 55f), listOf(55f, 60f)), ends)
        // Midpoint of the first arc (t = 0.5) lies on the circle within the Bézier approximation error.
        val c = r.ops[1].second
        val mx = 0.125f * 55f + 0.375f * c[0] + 0.375f * c[2] + 0.125f * c[4]
        val my = 0.125f * 60f + 0.375f * c[1] + 0.375f * c[3] + 0.125f * c[5]
        assertEquals(5f, hypot(mx - 50f, my - 60f), 0.01f)
    }

    @Test
    fun drawableRejectsBrokenStrokes() {
        assertTrue(StrokeGeometry.isDrawable(stroke(1f, 2f)))
        assertFalse(StrokeGeometry.isDrawable(stroke()))
        assertFalse(StrokeGeometry.isDrawable(stroke(1f, 2f, width = 0f)))
        assertFalse(StrokeGeometry.isDrawable(stroke(1f, 2f, width = Float.NaN)))
        assertFalse(StrokeGeometry.isDrawable(stroke(1f, Float.POSITIVE_INFINITY, 3f, 4f)))
        assertFalse(StrokeGeometry.isDrawable(stroke(1f, 2f, 3f, Float.NaN)))
    }

    @Test
    fun dotDetection() {
        assertTrue(StrokeGeometry.isDot(stroke(5f, 5f)))
        assertTrue(StrokeGeometry.isDot(stroke(5f, 5f, 5f, 5f, 5f, 5f)))
        assertFalse(StrokeGeometry.isDot(stroke(5f, 5f, 6f, 5f)))
        // Butt caps draw nothing for a zero-length line on screen, so no dot either.
        assertFalse(StrokeGeometry.isDot(stroke(5f, 5f, 5f, 5f, flatCaps = true)))
        assertTrue(StrokeGeometry.isDot(stroke(5f, 5f, flatCaps = true)))
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
