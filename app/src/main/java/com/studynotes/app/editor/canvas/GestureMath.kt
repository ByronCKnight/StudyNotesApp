package com.studynotes.app.editor.canvas

/** Pure gesture rules for [DocumentCanvasView] (no Android types, unit-tested). */
internal object GesturePolicy {
    /** A second finger this soon after the first one means pan/zoom, not a disturbance. */
    const val SECOND_FINGER_WINDOW_MS = 150L

    /** Before the stroke has travelled this many touch slops, a second finger still means pan/zoom. */
    const val SECOND_FINGER_SLOP_FACTOR = 3f

    /**
     * Finger drawing: when a second finger lands [elapsedMs] after the first, whose stroke has moved
     * at most [maxMovePx] from its start, true = discard the stroke and pan/zoom; false = the stroke is
     * established, so keep drawing and ignore the extra finger (e.g. the thumb holding a phone).
     */
    fun secondFingerCancelsStroke(elapsedMs: Long, maxMovePx: Float, touchSlopPx: Float): Boolean =
        elapsedMs <= SECOND_FINGER_WINDOW_MS || maxMovePx < SECOND_FINGER_SLOP_FACTOR * touchSlopPx
}

/**
 * Carries the part of a pan delta that a Float scroll offset could not represent. Deep into a long
 * document at high zoom the offsets exceed 2^24 px, where Float spacing is 2 px or more, so slow pans
 * would otherwise be rounded away entirely. The remainder is dropped at the scroll bounds.
 */
internal class PanRemainder {
    private var remainder = 0.0

    fun reset() {
        remainder = 0.0
    }

    /** The scroll offset after moving [current] by [delta], clamped to [min]..[max] (min ≤ max). */
    fun next(current: Float, delta: Float, min: Float, max: Float): Float {
        val wanted = current.toDouble() + delta + remainder
        val target = wanted.toFloat()
        val clamped = target.coerceIn(min, max)
        remainder = if (clamped != target) 0.0 else wanted - clamped
        return clamped
    }
}
