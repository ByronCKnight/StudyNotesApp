package com.studynotes.app.export

import com.studynotes.app.model.Stroke

/** Receives path construction operators; implemented over a PDF content stream (and by tests). */
internal interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun closePath()
}

/**
 * Stroke outlines for the vector export, matching AnnotationPainter's on-screen smoothing:
 * quadratic Béziers through the midpoints of consecutive points. PDF has no quadratic curve
 * operator, so each quadratic is emitted as the equivalent cubic.
 */
internal object StrokeGeometry {
    /** Control-point distance for a quarter circle drawn with one cubic Bézier. */
    private const val KAPPA = 0.5522848f

    /** False for strokes PDF operators would reject or that cannot be drawn (non-finite data, no width). */
    fun isDrawable(stroke: Stroke): Boolean =
        stroke.width.isFinite() && stroke.width > 0f && stroke.points.size >= 2 &&
            stroke.points.all { it.isFinite() }

    /**
     * True when the stroke renders as a round dot: a single point, or (with round caps) points that
     * all coincide — a zero-length path that the screen renderer draws as a cap-sized dot.
     */
    fun isDot(stroke: Stroke): Boolean {
        val p = stroke.points
        if (stroke.pointCount <= 1) return true
        if (stroke.flatCaps) return false
        for (i in 2 until stroke.pointCount * 2 step 2) {
            if (p[i] != p[0] || p[i + 1] != p[1]) return false
        }
        return true
    }

    /**
     * Emits the smoothed open path through interleaved [points] (at least two points):
     * moveTo(p0), then for every interior point p[i] a quadratic with control p[i] ending at the
     * midpoint of p[i] and p[i+1], then a line to the last point.
     */
    fun smoothPath(points: FloatArray, sink: PathSink) {
        val count = points.size / 2
        var x = points[0]
        var y = points[1]
        sink.moveTo(x, y)
        for (i in 1 until count - 1) {
            val qx = points[2 * i]
            val qy = points[2 * i + 1]
            val mx = (qx + points[2 * i + 2]) / 2f
            val my = (qy + points[2 * i + 3]) / 2f
            quadTo(x, y, qx, qy, mx, my, sink)
            x = mx
            y = my
        }
        sink.lineTo(points[2 * count - 2], points[2 * count - 1])
    }

    /**
     * Emits the quadratic Bézier from the current point ([x0], [y0]) with control ([qx], [qy]) to
     * ([x2], [y2]) as its exact cubic equivalent: c1 = p0 + 2/3 (q - p0), c2 = p2 + 2/3 (q - p2).
     */
    fun quadTo(x0: Float, y0: Float, qx: Float, qy: Float, x2: Float, y2: Float, sink: PathSink) {
        sink.curveTo(
            x0 + 2f / 3f * (qx - x0), y0 + 2f / 3f * (qy - y0),
            x2 + 2f / 3f * (qx - x2), y2 + 2f / 3f * (qy - y2),
            x2, y2,
        )
    }

    /** Emits a closed circle of [radius] around ([cx], [cy]) as four cubic arcs. */
    fun circle(cx: Float, cy: Float, radius: Float, sink: PathSink) {
        val k = KAPPA * radius
        sink.moveTo(cx + radius, cy)
        sink.curveTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius)
        sink.curveTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy)
        sink.curveTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius)
        sink.curveTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy)
        sink.closePath()
    }
}
