package com.studynotes.app.editor.canvas

import com.studynotes.app.model.InkTool
import com.studynotes.app.model.Stroke
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Growable buffer of interleaved x,y floats. Used for live strokes so input handling does not box
 * or reallocate per point.
 */
class PointBuffer(initialPoints: Int = 256) {
    private var data = FloatArray(initialPoints.coerceAtLeast(1) * 2)

    /** Number of points (not floats). */
    var pointCount = 0
        private set

    fun x(i: Int): Float = data[2 * i]
    fun y(i: Int): Float = data[2 * i + 1]

    fun add(x: Float, y: Float) {
        val n = pointCount * 2
        if (n + 2 > data.size) data = data.copyOf(data.size * 2)
        data[n] = x
        data[n + 1] = y
        pointCount++
    }

    /** Replaces point [i]. */
    fun set(i: Int, x: Float, y: Float) {
        data[2 * i] = x
        data[2 * i + 1] = y
    }

    /** Keeps only the first [points] points. */
    fun truncate(points: Int) {
        pointCount = min(pointCount, points.coerceAtLeast(0))
    }

    fun clear() {
        pointCount = 0
    }

    fun toArray(): FloatArray = data.copyOf(pointCount * 2)
}

/**
 * Pure ink geometry: simplification, smoothing, hit testing and snap-to-text plausibility.
 * All coordinates are page points unless stated otherwise. No Android types, so it is unit-tested.
 */
object InkGeometry {

    /** RDP tolerance for pen strokes (page points). */
    const val PEN_SIMPLIFY_TOLERANCE = 0.15f

    /** RDP tolerance for highlighter strokes (page points). */
    const val HIGHLIGHTER_SIMPLIFY_TOLERANCE = 0.35f

    /** Snap-to-text: both selection points are moved down by this fraction of the highlighter width. */
    const val SNAP_BASELINE_BIAS = 0.25f

    /** Snapped highlight width relative to the text line height. */
    const val SNAP_WIDTH_FACTOR = 1.15f

    fun simplifyTolerance(tool: InkTool): Float =
        if (tool == InkTool.PEN) PEN_SIMPLIFY_TOLERANCE else HIGHLIGHTER_SIMPLIFY_TOLERANCE

    /**
     * Ramer–Douglas–Peucker simplification of interleaved [points]. Always keeps the first and last
     * point. Iterative (explicit stack) so very long strokes cannot overflow the call stack.
     */
    fun simplify(points: FloatArray, tolerance: Float): FloatArray {
        val n = points.size / 2
        if (n <= 2) return points.copyOf(n * 2)
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val tolSq = tolerance * tolerance
        val stack = IntArray(2 * n)
        var top = 0
        stack[top++] = 0
        stack[top++] = n - 1
        while (top > 0) {
            val end = stack[--top]
            val start = stack[--top]
            if (end - start < 2) continue
            val ax = points[2 * start]
            val ay = points[2 * start + 1]
            val bx = points[2 * end]
            val by = points[2 * end + 1]
            var maxDist = -1f
            var maxIndex = -1
            for (i in start + 1 until end) {
                val d = pointSegmentDistanceSq(points[2 * i], points[2 * i + 1], ax, ay, bx, by)
                if (d > maxDist) {
                    maxDist = d
                    maxIndex = i
                }
            }
            if (maxDist > tolSq) {
                keep[maxIndex] = true
                stack[top++] = start
                stack[top++] = maxIndex
                stack[top++] = maxIndex
                stack[top++] = end
            }
        }
        var count = 0
        for (k in keep) if (k) count++
        val out = FloatArray(count * 2)
        var j = 0
        for (i in 0 until n) {
            if (!keep[i]) continue
            out[j++] = points[2 * i]
            out[j++] = points[2 * i + 1]
        }
        return out
    }

    /**
     * Light (1,2,1)/4 moving average over interior points; endpoints are kept exactly. Hides the
     * small diagonal jitter of digitisers without visibly rounding corners at pen sample rates.
     */
    fun smooth(points: FloatArray): FloatArray {
        val n = points.size / 2
        if (n < 3) return points.copyOf(n * 2)
        val out = points.copyOf(n * 2)
        for (i in 1 until n - 1) {
            out[2 * i] = (points[2 * i - 2] + 2f * points[2 * i] + points[2 * i + 2]) / 4f
            out[2 * i + 1] = (points[2 * i - 1] + 2f * points[2 * i + 1] + points[2 * i + 3]) / 4f
        }
        return out
    }

    /** Total length of the polyline. */
    fun polylineLength(points: FloatArray): Float {
        var len = 0f
        var i = 2
        while (i + 1 < points.size) {
            len += hypot(points[i] - points[i - 2], points[i + 1] - points[i - 1])
            i += 2
        }
        return len
    }

    /** True when every point lies within [tolerance] of the first point (a tap rather than a line). */
    fun isDot(points: FloatArray, tolerance: Float): Boolean {
        if (points.size < 2) return true
        val x0 = points[0]
        val y0 = points[1]
        val tolSq = tolerance * tolerance
        var i = 2
        while (i + 1 < points.size) {
            val dx = points[i] - x0
            val dy = points[i + 1] - y0
            if (dx * dx + dy * dy > tolSq) return false
            i += 2
        }
        return true
    }

    /** Squared distance from (px,py) to the segment a–b (a degenerate segment is a point). */
    fun pointSegmentDistanceSq(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        var t = if (lenSq > 0f) ((px - ax) * dx + (py - ay) * dy) / lenSq else 0f
        t = t.coerceIn(0f, 1f)
        val cx = ax + t * dx - px
        val cy = ay + t * dy - py
        return cx * cx + cy * cy
    }

    /** Distance between segments a1–a2 and b1–b2 (0 when they cross). */
    fun segmentSegmentDistance(
        a1x: Float, a1y: Float, a2x: Float, a2y: Float,
        b1x: Float, b1y: Float, b2x: Float, b2y: Float,
    ): Float {
        val d1 = cross(b2x - b1x, b2y - b1y, a1x - b1x, a1y - b1y)
        val d2 = cross(b2x - b1x, b2y - b1y, a2x - b1x, a2y - b1y)
        val d3 = cross(a2x - a1x, a2y - a1y, b1x - a1x, b1y - a1y)
        val d4 = cross(a2x - a1x, a2y - a1y, b2x - a1x, b2y - a1y)
        if (((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))) {
            return 0f
        }
        val m = min(
            min(pointSegmentDistanceSq(a1x, a1y, b1x, b1y, b2x, b2y), pointSegmentDistanceSq(a2x, a2y, b1x, b1y, b2x, b2y)),
            min(pointSegmentDistanceSq(b1x, b1y, a1x, a1y, a2x, a2y), pointSegmentDistanceSq(b2x, b2y, a1x, a1y, a2x, a2y)),
        )
        return sqrt(m)
    }

    /** Distance from point (px,py) to the polyline [points] (a single point counts as a polyline). */
    fun distanceToPolyline(px: Float, py: Float, points: FloatArray): Float =
        segmentDistanceToPolyline(px, py, px, py, points)

    /** Smallest distance between the segment a–b and the polyline [points]. */
    fun segmentDistanceToPolyline(ax: Float, ay: Float, bx: Float, by: Float, points: FloatArray): Float {
        val n = points.size / 2
        if (n == 0) return Float.POSITIVE_INFINITY
        if (n == 1) return sqrt(pointSegmentDistanceSq(points[0], points[1], ax, ay, bx, by))
        var best = Float.POSITIVE_INFINITY
        for (i in 1 until n) {
            val d = segmentSegmentDistance(
                ax, ay, bx, by,
                points[2 * i - 2], points[2 * i - 1], points[2 * i], points[2 * i + 1],
            )
            if (d < best) {
                best = d
                if (best == 0f) break
            }
        }
        return best
    }

    /**
     * Bounding box of [points] written to [out] as left, top, right, bottom. Empty input gives an
     * inverted (empty) box.
     */
    fun bounds(points: FloatArray, out: FloatArray) {
        var l = Float.POSITIVE_INFINITY
        var t = Float.POSITIVE_INFINITY
        var r = Float.NEGATIVE_INFINITY
        var b = Float.NEGATIVE_INFINITY
        var i = 0
        while (i + 1 < points.size) {
            val x = points[i]
            val y = points[i + 1]
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
            i += 2
        }
        out[0] = l
        out[1] = t
        out[2] = r
        out[3] = b
    }

    /**
     * Eraser hit test: true when the eraser path segment a–b, with [radius], touches [stroke]
     * (its polyline within radius + half the stroke width). [strokeBounds] (left, top, right, bottom
     * of the stroke's points, see [bounds]) is used for a quick reject.
     */
    fun eraserHits(
        stroke: Stroke,
        strokeBounds: FloatArray,
        ax: Float, ay: Float, bx: Float, by: Float,
        radius: Float,
    ): Boolean {
        val reach = radius + stroke.width / 2f
        if (max(ax, bx) < strokeBounds[0] - reach || min(ax, bx) > strokeBounds[2] + reach ||
            max(ay, by) < strokeBounds[1] - reach || min(ay, by) > strokeBounds[3] + reach
        ) {
            return false
        }
        return segmentDistanceToPolyline(ax, ay, bx, by, stroke.points) <= reach
    }

    /**
     * Expands erased [hitIds] to whole groups: every stroke in [strokes] that shares a groupId with
     * a hit stroke is included. Returns a new set containing [hitIds] and the group members.
     */
    fun expandGroups(strokes: List<Stroke>, hitIds: Collection<String>): Set<String> {
        val result = HashSet<String>(hitIds)
        val groups = HashSet<String>()
        for (i in 0 until strokes.size) {
            val s = strokes[i]
            val g = s.groupId ?: continue
            if (s.id in result) groups.add(g)
        }
        if (groups.isEmpty()) return result
        for (i in 0 until strokes.size) {
            val s = strokes[i]
            if (s.groupId != null && s.groupId in groups) result.add(s.id)
        }
        return result
    }

    /**
     * Decides whether text-selection rectangles are a plausible snap for a freehand highlight.
     *
     * @param rects interleaved left, top, right, bottom per text line (page points).
     * @param strokePoints the freehand stroke points (page points).
     * @param highlighterWidth the highlighter width (page points).
     *
     * Accepted when non-empty and: each rect is at most 3× the highlighter width tall; the total
     * vertical span of the rects is at most the stroke's vertical span plus twice the tallest rect;
     * and at least 60 % of the stroke's points lie inside the rects inflated by half their height.
     */
    fun snapPlausible(rects: FloatArray, strokePoints: FloatArray, highlighterWidth: Float): Boolean {
        val rectCount = rects.size / 4
        val pointCount = strokePoints.size / 2
        if (rectCount == 0 || pointCount == 0) return false
        var minTop = Float.POSITIVE_INFINITY
        var maxBottom = Float.NEGATIVE_INFINITY
        var maxHeight = 0f
        for (r in 0 until rectCount) {
            val l = rects[4 * r]
            val t = rects[4 * r + 1]
            val rr = rects[4 * r + 2]
            val b = rects[4 * r + 3]
            val h = b - t
            if (h <= 0f || rr <= l || h > 3f * highlighterWidth) return false
            minTop = min(minTop, t)
            maxBottom = max(maxBottom, b)
            maxHeight = max(maxHeight, h)
        }
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until pointCount) {
            val y = strokePoints[2 * i + 1]
            minY = min(minY, y)
            maxY = max(maxY, y)
        }
        if (maxBottom - minTop > (maxY - minY) + 2f * maxHeight) return false
        var inside = 0
        for (i in 0 until pointCount) {
            val x = strokePoints[2 * i]
            val y = strokePoints[2 * i + 1]
            for (r in 0 until rectCount) {
                val t = rects[4 * r + 1]
                val b = rects[4 * r + 3]
                val pad = (b - t) / 2f
                if (x >= rects[4 * r] - pad && x <= rects[4 * r + 2] + pad && y >= t - pad && y <= b + pad) {
                    inside++
                    break
                }
            }
        }
        return inside >= 0.6f * pointCount
    }

    /**
     * Builds the snapped highlight: one flat-capped stroke per text line rect (interleaved l,t,r,b),
     * drawn through the line's vertical centre at 1.15× its height, all sharing [groupId].
     */
    fun snappedStrokes(rects: FloatArray, color: Int, groupId: String, newId: () -> String): List<Stroke> {
        val count = rects.size / 4
        val out = ArrayList<Stroke>(count)
        for (r in 0 until count) {
            val l = rects[4 * r]
            val t = rects[4 * r + 1]
            val rr = rects[4 * r + 2]
            val b = rects[4 * r + 3]
            val cy = (t + b) / 2f
            out.add(
                Stroke(
                    id = newId(),
                    tool = InkTool.HIGHLIGHTER,
                    color = color,
                    width = (b - t) * SNAP_WIDTH_FACTOR,
                    points = floatArrayOf(l, cy, rr, cy),
                    flatCaps = true,
                    groupId = groupId,
                ),
            )
        }
        return out
    }

    /**
     * The snapped highlight for a freehand stroke ([strokePoints]) over the text-line [rects], or
     * null when the snap isn't plausible. Text running down or up the screen (a page with /Rotate
     * 90 or 270) is handled by swapping x and y, so the stroke follows the line, not across it.
     */
    fun snapStrokes(
        rects: FloatArray,
        strokePoints: FloatArray,
        highlighterWidth: Float,
        color: Int,
        groupId: String,
        newId: () -> String,
    ): List<Stroke>? {
        if (!isVerticalText(rects, strokePoints)) {
            if (!snapPlausible(rects, strokePoints, highlighterWidth)) return null
            return snappedStrokes(rects, color, groupId, newId)
        }
        val swappedRects = swapAxesOfRects(rects)
        if (!snapPlausible(swappedRects, swapAxes(strokePoints), highlighterWidth)) return null
        return snappedStrokes(swappedRects, color, groupId, newId).map { s ->
            Stroke(s.id, s.tool, s.color, s.width, swapAxes(s.points), s.flatCaps, s.groupId)
        }
    }

    /**
     * The area each snapped stroke (from [snapStrokes]) will cover, as interleaved l, t, r, b per
     * stroke: the band of its width around its two-point segment. Drawn as the selection preview
     * while the highlight is being dragged, so what is shown is exactly what the lift commits.
     */
    fun snapPreviewRects(strokes: List<Stroke>): FloatArray {
        val out = FloatArray(strokes.size * 4)
        for (i in strokes.indices) {
            val s = strokes[i]
            val p = s.points
            val x0 = p[0]
            val y0 = p[1]
            val x1 = if (p.size >= 4) p[p.size - 2] else x0
            val y1 = if (p.size >= 4) p[p.size - 1] else y0
            val half = s.width / 2f
            // The band runs along the segment: across it the stroke is as wide as its width.
            val horizontal = abs(x1 - x0) >= abs(y1 - y0)
            out[4 * i] = min(x0, x1) - if (horizontal) 0f else half
            out[4 * i + 1] = min(y0, y1) - if (horizontal) half else 0f
            out[4 * i + 2] = max(x0, x1) + if (horizontal) 0f else half
            out[4 * i + 3] = max(y0, y1) + if (horizontal) half else 0f
        }
        return out
    }

    /**
     * True when every rect (interleaved l, t, r, b) is taller than wide and the stroke runs more
     * vertically than horizontally: lines of text that read down or up the screen.
     */
    fun isVerticalText(rects: FloatArray, strokePoints: FloatArray): Boolean {
        val rectCount = rects.size / 4
        val pointCount = strokePoints.size / 2
        if (rectCount == 0 || pointCount < 2) return false
        for (r in 0 until rectCount) {
            if (rects[4 * r + 3] - rects[4 * r + 1] <= rects[4 * r + 2] - rects[4 * r]) return false
        }
        var minX = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (i in 0 until pointCount) {
            minX = min(minX, strokePoints[2 * i])
            maxX = max(maxX, strokePoints[2 * i])
            minY = min(minY, strokePoints[2 * i + 1])
            maxY = max(maxY, strokePoints[2 * i + 1])
        }
        return maxY - minY > maxX - minX
    }

    /** Interleaved x,y points with x and y swapped. */
    fun swapAxes(points: FloatArray): FloatArray = FloatArray(points.size) { i -> points[i xor 1] }

    /** Interleaved l,t,r,b rects with x and y swapped (l,t,r,b -> t,l,b,r). */
    fun swapAxesOfRects(rects: FloatArray): FloatArray = swapAxes(rects)

    private fun cross(ax: Float, ay: Float, bx: Float, by: Float): Float = ax * by - ay * bx
}

/**
 * Hold-to-straighten detection. Distances are in screen pixels, times in milliseconds.
 * The pen "holds" when it stays within [radiusPx] of an anchor for [holdMillis]; the anchor moves
 * whenever the pen leaves that radius. Straightening is only offered once the stroke is longer than
 * [minLengthPx] and fires at most once per stroke.
 */
class HoldDetector(
    private val radiusPx: Float,
    private val holdMillis: Long,
    private val minLengthPx: Float,
) {
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorTime = 0L
    private var fired = false

    fun start(x: Float, y: Float, timeMs: Long) {
        anchorX = x
        anchorY = y
        anchorTime = timeMs
        fired = false
    }

    /** Feeds a new pen position. */
    fun move(x: Float, y: Float, timeMs: Long) {
        val dx = x - anchorX
        val dy = y - anchorY
        if (dx * dx + dy * dy > radiusPx * radiusPx) {
            anchorX = x
            anchorY = y
            anchorTime = timeMs
        }
    }

    /** True (once) when the stroke ([lengthPx] long so far) should become a straight line at [nowMs]. */
    fun shouldStraighten(nowMs: Long, lengthPx: Float): Boolean {
        if (fired || lengthPx <= minLengthPx || nowMs - anchorTime < holdMillis) return false
        fired = true
        return true
    }

    /** Milliseconds until the current hold could complete (at least 1). */
    fun millisUntilDue(nowMs: Long): Long = (anchorTime + holdMillis - nowMs).coerceAtLeast(1L)

    /** True once a stroke of [lengthPx] is long enough to be straightened. */
    fun isLongEnough(lengthPx: Float): Boolean = lengthPx > minLengthPx

    /**
     * Delay before the next hold check for a stroke [lengthPx] long, or [NO_CHECK] when polling is
     * pointless: the hold already fired, or the stroke is still too short (only drawing further can
     * change that, so the caller re-arms the check from the move handler once [isLongEnough]).
     */
    fun nextCheckMillis(nowMs: Long, lengthPx: Float): Long =
        if (fired || !isLongEnough(lengthPx)) NO_CHECK else millisUntilDue(nowMs)

    companion object {
        /** [nextCheckMillis]: do not schedule another check. */
        const val NO_CHECK = -1L
    }
}
