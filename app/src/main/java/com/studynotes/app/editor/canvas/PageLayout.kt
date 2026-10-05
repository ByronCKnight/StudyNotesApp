package com.studynotes.app.editor.canvas

import com.studynotes.app.model.ViewState
import kotlin.math.max
import kotlin.math.min

/**
 * Pure geometry of the vertically stacked page strip (no Android types, unit-tested).
 *
 * Coordinate systems:
 * - **Unit space**: the strip at zoom 1 in pixels, x in `0..fitWidth` across a page, y = 0 at the top
 *   of the first page. Every page is exactly [fitWidth] wide (each is scaled by its own width), pages
 *   are separated by [gap].
 * - **Content space**: unit space scaled by the zoom plus fixed insets. Content x = [marginLeft] +
 *   zoom × ux, content y = [topInset] + zoom × uy. The side margins and the top/bottom insets do not
 *   scale, the inter-page gaps do (so the strip scales uniformly and zoom-around-focus is exact).
 *   Each side margin is [sideMargin] plus that side's safe inset (navigation bar / display cutout),
 *   so fit-width pages lay out inside the safe area while the backdrop still fills the view.
 * - **View space**: content minus the scroll offset. A scroll smaller than zero centres content
 *   that is smaller than the view.
 *
 * The view-state anchor ("reference line") is the view y where the first page's top sits at scroll 0
 * ([topInset]), so `ViewState(page = 0, offset = 0)` is exactly scroll 0 at any zoom.
 */
class PageLayout(
    /** Page widths in page points (each > 0). */
    private val pageWidthsPt: FloatArray,
    /** Page heights in page points (each > 0). */
    private val pageHeightsPt: FloatArray,
) {
    init {
        require(pageWidthsPt.size == pageHeightsPt.size) { "width/height count mismatch" }
    }

    val pageCount: Int get() = pageWidthsPt.size

    var viewWidth = 0f
        private set
    var viewHeight = 0f
        private set
    var sideMargin = 0f
        private set

    /** Horizontal safe-area insets (px) added to the side margin on each side. */
    var insetLeft = 0f
        private set
    var insetRight = 0f
        private set

    /** Content x of the pages' left edge: side margin plus the left safe inset. */
    val marginLeft: Float get() = sideMargin + insetLeft

    /** Space right of fit-width pages: side margin plus the right safe inset. */
    val marginRight: Float get() = sideMargin + insetRight
    var gap = 0f
        private set
    var paddingTop = 0f
        private set
    var paddingBottom = 0f
        private set

    /** Width of every page at zoom 1, in px. */
    var fitWidth = 1f
        private set

    /** Page tops in unit space. */
    private val unitTops = FloatArray(pageWidthsPt.size)
    private val unitHeights = FloatArray(pageWidthsPt.size)

    /** Height of the whole strip (first page top to last page bottom) in unit space. */
    var unitStripHeight = 0f
        private set

    /** View y of the reference line (also the content y of the first page top at zoom 0). */
    val topInset: Float get() = paddingTop + gap

    /** True once [configure] was called with a non-empty view. */
    val isConfigured: Boolean get() = viewWidth > 0f && viewHeight > 0f

    /**
     * Sets the view metrics. [sideMargin] is kept on each side of fit-width pages; [gap] separates
     * pages; the paddings add scrollable space under overlaid toolbars; [insetLeft]/[insetRight] are
     * horizontal safe-area insets (side navigation bar, display cutout) kept clear of the pages.
     */
    fun configure(
        viewWidth: Float,
        viewHeight: Float,
        sideMargin: Float,
        gap: Float,
        paddingTop: Float,
        paddingBottom: Float,
        insetLeft: Float = 0f,
        insetRight: Float = 0f,
    ) {
        this.viewWidth = viewWidth
        this.viewHeight = viewHeight
        this.sideMargin = sideMargin
        this.gap = gap
        this.paddingTop = paddingTop
        this.paddingBottom = paddingBottom
        this.insetLeft = max(0f, insetLeft)
        this.insetRight = max(0f, insetRight)
        fitWidth = max(1f, viewWidth - marginLeft - marginRight)
        var y = 0f
        for (i in 0 until pageCount) {
            unitTops[i] = y
            val h = fitWidth * pageHeightsPt[i] / pageWidthsPt[i]
            unitHeights[i] = h
            y += h + gap
        }
        unitStripHeight = if (pageCount == 0) 0f else y - gap
    }

    fun pageWidthPt(page: Int): Float = pageWidthsPt[page]
    fun pageHeightPt(page: Int): Float = pageHeightsPt[page]

    /** Pixels per page point of [page] at [zoom]. */
    fun pxPerPt(page: Int, zoom: Float): Float = fitWidth / pageWidthsPt[page] * zoom

    /** Pixels per page point of [page] at zoom 1 (the fit-width scale). */
    fun fitScale(page: Int): Float = fitWidth / pageWidthsPt[page]

    fun unitTop(page: Int): Float = unitTops[page]
    fun unitHeight(page: Int): Float = unitHeights[page]

    fun contentWidth(zoom: Float): Float = marginLeft + marginRight + zoom * fitWidth
    fun contentHeight(zoom: Float): Float = topInset + zoom * unitStripHeight + gap + paddingBottom

    /** Content x of every page's left edge. */
    fun pageLeft(): Float = marginLeft
    fun pageWidth(zoom: Float): Float = zoom * fitWidth
    fun pageTop(page: Int, zoom: Float): Float = topInset + zoom * unitTops[page]
    fun pageHeight(page: Int, zoom: Float): Float = zoom * unitHeights[page]

    /** Converts a view y to unit y. */
    fun unitYAt(viewY: Float, scrollY: Float, zoom: Float): Float = (scrollY + viewY - topInset) / zoom

    /** Converts a view x to unit x. */
    fun unitXAt(viewX: Float, scrollX: Float, zoom: Float): Float = (scrollX + viewX - marginLeft) / zoom

    /**
     * Index of the last page whose top is at or above [unitY] (gaps belong to the page above);
     * 0 above the strip, the last page below it. -1 when there are no pages.
     */
    fun pageAtUnitY(unitY: Float): Int {
        if (pageCount == 0) return -1
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (unitTops[mid] <= unitY) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The page containing view point ([viewX], [viewY]) or -1 (outside every page or in a gap). */
    fun pageAtView(viewX: Float, viewY: Float, scrollX: Float, scrollY: Float, zoom: Float): Int {
        if (pageCount == 0) return -1
        val ux = unitXAt(viewX, scrollX, zoom)
        if (ux < 0f || ux > fitWidth) return -1
        val uy = unitYAt(viewY, scrollY, zoom)
        val page = pageAtUnitY(uy)
        return if (uy >= unitTops[page] && uy <= unitTops[page] + unitHeights[page]) page else -1
    }

    /** First page that may be visible in the view at ([scrollY], [zoom]). */
    fun firstVisiblePage(scrollY: Float, zoom: Float): Int {
        if (pageCount == 0) return -1
        val uy = unitYAt(0f, scrollY, zoom)
        val p = pageAtUnitY(uy)
        // In the gap below page p: page p is entirely above the view.
        return if (uy > unitTops[p] + unitHeights[p] && p < pageCount - 1) p + 1 else p
    }

    /** Last page that may be visible in the view at ([scrollY], [zoom]). */
    fun lastVisiblePage(scrollY: Float, zoom: Float): Int =
        if (pageCount == 0) -1 else pageAtUnitY(unitYAt(viewHeight, scrollY, zoom))

    /** The page under the centre of the unobscured area (between the paddings). */
    fun pageAtViewportCentre(scrollY: Float, zoom: Float): Int {
        if (pageCount == 0) return -1
        val centre = paddingTop + (viewHeight - paddingTop - paddingBottom) / 2f
        return pageAtUnitY(unitYAt(centre, scrollY, zoom))
    }

    /** Clamps a horizontal scroll so content edges stay inside the view; centres narrow content. */
    fun clampScrollX(scrollX: Float, zoom: Float): Float = clampAxis(scrollX, contentWidth(zoom), viewWidth)

    /** Clamps a vertical scroll so content edges stay inside the view; centres short content. */
    fun clampScrollY(scrollY: Float, zoom: Float): Float = clampAxis(scrollY, contentHeight(zoom), viewHeight)

    fun minScrollX(zoom: Float): Float = clampAxis(Float.NEGATIVE_INFINITY, contentWidth(zoom), viewWidth)
    fun maxScrollX(zoom: Float): Float = clampAxis(Float.POSITIVE_INFINITY, contentWidth(zoom), viewWidth)
    fun minScrollY(zoom: Float): Float = clampAxis(Float.NEGATIVE_INFINITY, contentHeight(zoom), viewHeight)
    fun maxScrollY(zoom: Float): Float = clampAxis(Float.POSITIVE_INFINITY, contentHeight(zoom), viewHeight)

    /**
     * Horizontal scroll after changing the zoom from [oldZoom] to [newZoom] so that the document
     * point under view x [focusX] stays under it. Not clamped.
     */
    fun zoomAroundScrollX(scrollX: Float, focusX: Float, oldZoom: Float, newZoom: Float): Float {
        val ux = (scrollX + focusX - marginLeft) / oldZoom
        return marginLeft + ux * newZoom - focusX
    }

    /** Vertical counterpart of [zoomAroundScrollX]. Not clamped. */
    fun zoomAroundScrollY(scrollY: Float, focusY: Float, oldZoom: Float, newZoom: Float): Float {
        val uy = (scrollY + focusY - topInset) / oldZoom
        return topInset + uy * newZoom - focusY
    }

    /** Position of the reference line expressed as page + fraction (see [ViewState]). */
    fun viewStateAt(scrollY: Float, zoom: Float): ViewState {
        if (pageCount == 0) return ViewState(0, 0f, zoom)
        val uy = scrollY / zoom
        val p = pageAtUnitY(uy)
        val bottom = unitTops[p] + unitHeights[p]
        if (uy > bottom && p < pageCount - 1) return ViewState(p + 1, 0f, zoom)
        val fraction = ((uy - unitTops[p]) / unitHeights[p]).coerceIn(0f, 1f)
        return ViewState(p, fraction, zoom)
    }

    /** Vertical scroll that puts [page] + [fraction] on the reference line at [zoom]. Not clamped. */
    fun scrollYFor(page: Int, fraction: Float, zoom: Float): Float {
        if (pageCount == 0) return 0f
        val p = page.coerceIn(0, pageCount - 1)
        return zoom * (unitTops[p] + fraction.coerceIn(0f, 1f) * unitHeights[p])
    }

    /** Horizontal position of the view centre as a fraction of the page width (for size changes). */
    fun centreFractionX(scrollX: Float, zoom: Float): Float =
        (scrollX + viewWidth / 2f - marginLeft) / (zoom * fitWidth)

    /** Inverse of [centreFractionX]. Not clamped. */
    fun scrollXForCentreFraction(fraction: Float, zoom: Float): Float =
        marginLeft + fraction * zoom * fitWidth - viewWidth / 2f

    private fun clampAxis(scroll: Float, content: Float, view: Float): Float {
        val maxScroll = content - view
        return if (maxScroll <= 0f) maxScroll / 2f else min(max(scroll, 0f), maxScroll)
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 6f

        fun clampZoom(zoom: Float): Float = if (zoom.isNaN()) 1f else zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
    }
}
