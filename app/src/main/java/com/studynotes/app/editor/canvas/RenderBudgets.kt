package com.studynotes.app.editor.canvas

import kotlin.math.sqrt

/**
 * How many detail tiles may still be rendered for the current viewport. Rendering more tiles for
 * one viewport than the tile cache holds would make them evict each other forever, so the count is
 * capped per viewport (identified by its change stamp). Pure logic, main thread only.
 */
internal class TileBudget {
    private var stamp = Long.MIN_VALUE
    private var rendered = 0

    /** Starts a render pass for the viewport last changed at [viewportStamp] (new viewport = fresh budget). */
    fun beginPass(viewportStamp: Long) {
        if (stamp != viewportStamp) {
            stamp = viewportStamp
            rendered = 0
        }
    }

    fun isExhausted(capacity: Int): Boolean = rendered >= capacity

    fun onTileRendered() {
        rendered++
    }

    /**
     * Tiles were dropped without a viewport change (trim memory, fit-width change, new document):
     * the tiles already counted are gone, so the budget starts over.
     */
    fun reset() {
        stamp = Long.MIN_VALUE
        rendered = 0
    }
}

/** Sizing of the fit-width base bitmaps against the base cache budget. Pure logic. */
internal object BaseBudget {
    /** Share of the base cache the visible pages may take, leaving room so they never evict each other. */
    const val VISIBLE_SHARE = 0.9

    /**
     * Linear scale (≤ 1) for the visible pages' base bitmaps so that, together, they fit in
     * [VISIBLE_SHARE] of [capacityBytes]. 1 when [visibleBytes] (their full-size total) already fits.
     * Without it, visible pages that do not fit the cache evict each other and re-render forever.
     */
    fun visibleScale(visibleBytes: Long, capacityBytes: Long): Float {
        val allowed = capacityBytes * VISIBLE_SHARE
        if (visibleBytes <= 0L || visibleBytes <= allowed) return 1f
        return sqrt(allowed / visibleBytes).toFloat().coerceIn(MIN_SCALE, 1f)
    }

    /** Never shrink below this (a 1/16 area base is still better than a white page). */
    const val MIN_SCALE = 0.25f
}
