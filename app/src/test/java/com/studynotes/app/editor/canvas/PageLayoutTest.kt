package com.studynotes.app.editor.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PageLayoutTest {

    private val eps = 1e-3f

    // Portrait Letter, landscape A4, portrait Letter.
    private val layout = PageLayout(floatArrayOf(612f, 842f, 612f), floatArrayOf(792f, 595f, 792f))

    @Before
    fun setUp() {
        layout.configure(
            viewWidth = 1000f,
            viewHeight = 800f,
            sideMargin = 12f,
            gap = 12f,
            paddingTop = 50f,
            paddingBottom = 30f,
        )
    }

    private val fit = 976f
    private val h0 = fit * 792f / 612f
    private val h1 = fit * 595f / 842f

    @Test
    fun everyPageFitsTheWidthAndStacksWithGaps() {
        assertEquals(fit, layout.fitWidth, eps)
        assertEquals(0f, layout.unitTop(0), eps)
        assertEquals(h0 + 12f, layout.unitTop(1), eps)
        assertEquals(h0 + 12f + h1 + 12f, layout.unitTop(2), eps)
        assertEquals(h1, layout.unitHeight(1), eps)
        assertEquals(2 * h0 + h1 + 24f, layout.unitStripHeight, eps)
        // Each page is scaled by its own width.
        assertEquals(fit / 612f, layout.pxPerPt(0, 1f), eps)
        assertEquals(fit / 842f * 2f, layout.pxPerPt(1, 2f), eps)
    }

    @Test
    fun contentPositionsScaleWithZoomButInsetsDoNot() {
        assertEquals(62f, layout.topInset, eps)
        assertEquals(62f + 2f * (h0 + 12f), layout.pageTop(1, 2f), eps)
        assertEquals(12f, layout.pageLeft(), eps)
        assertEquals(1000f, layout.contentWidth(1f), eps)
        assertEquals(24f + 2f * fit, layout.contentWidth(2f), eps)
        assertEquals(62f + 3f * layout.unitStripHeight + 12f + 30f, layout.contentHeight(3f), eps)
    }

    @Test
    fun pageLookupByY() {
        assertEquals(0, layout.pageAtUnitY(-50f))
        assertEquals(0, layout.pageAtUnitY(10f))
        assertEquals(0, layout.pageAtUnitY(h0 + 5f)) // the gap belongs to the page above
        assertEquals(1, layout.pageAtUnitY(h0 + 12f))
        assertEquals(2, layout.pageAtUnitY(1e9f))
        // pageAtView rejects gaps and the side margins.
        val gapY = 62f + h0 + 6f
        assertEquals(-1, layout.pageAtView(500f, gapY, 0f, 0f, 1f))
        assertEquals(-1, layout.pageAtView(5f, 100f, 0f, 0f, 1f))
        assertEquals(0, layout.pageAtView(500f, 100f, 0f, 0f, 1f))
        assertEquals(1, layout.pageAtView(500f, 62f + h0 + 20f, 0f, 0f, 1f))
    }

    @Test
    fun visibleRange() {
        assertEquals(0, layout.firstVisiblePage(0f, 1f))
        assertEquals(0, layout.lastVisiblePage(0f, 1f))
        // Scroll so the view top sits in the gap below page 0 (view y 0 is unit y scroll - topInset).
        val scroll = h0 + 4f + 62f
        assertEquals(1, layout.firstVisiblePage(scroll, 1f))
        assertEquals(2, layout.lastVisiblePage(scroll, 1f))
    }

    @Test
    fun viewStateRoundTrip() {
        for (zoom in floatArrayOf(0.5f, 1f, 2.37f, 6f)) {
            for (page in 0..2) {
                for (fraction in floatArrayOf(0f, 0.25f, 0.5f, 0.999f)) {
                    val scroll = layout.scrollYFor(page, fraction, zoom)
                    val vs = layout.viewStateAt(scroll, zoom)
                    assertEquals(page, vs.page)
                    assertEquals(fraction, vs.pageOffsetFraction, 1e-4f)
                    assertEquals(zoom, vs.zoom, 0f)
                }
            }
        }
        // First page, offset 0 is exactly scroll 0 at any zoom.
        assertEquals(0f, layout.scrollYFor(0, 0f, 3f), 0f)
    }

    @Test
    fun viewStateInGapPointsAtTheNextPage() {
        val vs = layout.viewStateAt(h0 + 6f, 1f)
        assertEquals(1, vs.page)
        assertEquals(0f, vs.pageOffsetFraction, 0f)
    }

    @Test
    fun anchorSurvivesAResize() {
        val zoom = 1.7f
        val scrollY = layout.scrollYFor(1, 0.4f, zoom)
        val scrollX = 300f
        val anchor = layout.viewStateAt(scrollY, zoom)
        val centreFraction = layout.centreFractionX(scrollX, zoom)
        layout.configure(1600f, 900f, 12f, 12f, 50f, 30f)
        val newScrollY = layout.scrollYFor(anchor.page, anchor.pageOffsetFraction, zoom)
        val restored = layout.viewStateAt(newScrollY, zoom)
        assertEquals(1, restored.page)
        assertEquals(0.4f, restored.pageOffsetFraction, 1e-4f)
        val newScrollX = layout.scrollXForCentreFraction(centreFraction, zoom)
        assertEquals(centreFraction, layout.centreFractionX(newScrollX, zoom), 1e-5f)
    }

    @Test
    fun zoomAroundFocusKeepsTheFocalDocumentPointFixed() {
        val scrollX = 150f
        val scrollY = 900f
        val focusX = 420f
        val focusY = 333f
        val oldZoom = 1.5f
        val newZoom = 3.2f
        val ux = layout.unitXAt(focusX, scrollX, oldZoom)
        val uy = layout.unitYAt(focusY, scrollY, oldZoom)
        val sx = layout.zoomAroundScrollX(scrollX, focusX, oldZoom, newZoom)
        val sy = layout.zoomAroundScrollY(scrollY, focusY, oldZoom, newZoom)
        assertEquals(ux, layout.unitXAt(focusX, sx, newZoom), eps)
        assertEquals(uy, layout.unitYAt(focusY, sy, newZoom), eps)
    }

    @Test
    fun clampingKeepsContentEdgesInsideTheView() {
        // Zoomed in: scroll is limited to [0, content - view].
        assertEquals(0f, layout.clampScrollX(-100f, 2f), 0f)
        assertEquals(layout.contentWidth(2f) - 1000f, layout.clampScrollX(1e6f, 2f), eps)
        assertEquals(0f, layout.clampScrollY(-5f, 2f), 0f)
        assertEquals(layout.contentHeight(2f) - 800f, layout.clampScrollY(1e7f, 2f), eps)
        // Zoomed out: narrower content is centred (negative scroll), whatever was asked for.
        val centred = (layout.contentWidth(0.5f) - 1000f) / 2f
        assertTrue(centred < 0f)
        assertEquals(centred, layout.clampScrollX(0f, 0.5f), eps)
        assertEquals(centred, layout.clampScrollX(999f, 0.5f), eps)
        assertEquals(layout.minScrollX(0.5f), layout.maxScrollX(0.5f), 0f)
    }

    @Test
    fun shortDocumentIsCentredVertically() {
        val single = PageLayout(floatArrayOf(842f), floatArrayOf(595f))
        single.configure(400f, 2000f, 12f, 12f, 0f, 0f)
        val content = single.contentHeight(1f)
        assertTrue(content < 2000f)
        assertEquals((content - 2000f) / 2f, single.clampScrollY(123f, 1f), eps)
        // The page lands in the middle of the view.
        val top = single.pageTop(0, 1f) - single.clampScrollY(0f, 1f)
        val bottom = top + single.pageHeight(0, 1f)
        assertEquals(1000f, (top + bottom) / 2f, 1f)
    }

    @Test
    fun currentPageIsTheOneUnderTheViewportCentre() {
        // Unobscured area 50..770, centre at 410 => unit y 348 at scroll 0: page 0.
        assertEquals(0, layout.pageAtViewportCentre(0f, 1f))
        assertEquals(1, layout.pageAtViewportCentre(h0 + 12f - 348f + 1f, 1f))
    }

    @Test
    fun emptyDocument() {
        val empty = PageLayout(FloatArray(0), FloatArray(0))
        empty.configure(500f, 500f, 12f, 12f, 0f, 0f)
        assertEquals(-1, empty.pageAtUnitY(0f))
        assertEquals(-1, empty.firstVisiblePage(0f, 1f))
        assertEquals(-1, empty.pageAtView(10f, 10f, 0f, 0f, 1f))
        assertEquals(0, empty.viewStateAt(0f, 1f).page)
    }

    @Test
    fun horizontalSafeInsetsKeepFitWidthPagesClearOfSideBars() {
        // Regression (ui-ux-4): a 48 px navigation bar on the right in landscape.
        val l = PageLayout(floatArrayOf(612f), floatArrayOf(792f))
        l.configure(1000f, 800f, 12f, 12f, 0f, 0f, insetLeft = 20f, insetRight = 48f)
        assertEquals(1000f - 24f - 20f - 48f, l.fitWidth, eps)
        assertEquals(32f, l.pageLeft(), eps)
        // At fit width nothing scrolls and the page ends exactly at the right margin.
        assertEquals(1000f, l.contentWidth(1f), eps)
        val scroll = l.clampScrollX(0f, 1f)
        assertEquals(0f, scroll, eps)
        assertEquals(1000f - 48f - 12f, l.pageLeft() - scroll + l.pageWidth(1f), eps)
        // Hit-testing and zoom-around-focus use the shifted left margin.
        assertEquals(-1, l.pageAtView(25f, 100f, 0f, 0f, 1f))
        assertEquals(0, l.pageAtView(40f, 100f, 0f, 0f, 1f))
        val ux = l.unitXAt(400f, 100f, 1.5f)
        assertEquals(ux, l.unitXAt(400f, l.zoomAroundScrollX(100f, 400f, 1.5f, 3f), 3f), eps)
        // Zoomed out, the page is centred in the safe area, not in the whole view.
        val z = 0.5f
        val left = l.pageLeft() - l.clampScrollX(0f, z)
        val centre = left + l.pageWidth(z) / 2f
        assertEquals((20f + (1000f - 48f)) / 2f, centre, eps)
        // Fraction round trip still holds.
        val f = l.centreFractionX(150f, 2f)
        assertEquals(150f, l.scrollXForCentreFraction(f, 2f), 1e-2f)
    }

    @Test
    fun insetsDefaultToZero() {
        assertEquals(0f, layout.insetLeft, 0f)
        assertEquals(0f, layout.insetRight, 0f)
        assertEquals(layout.sideMargin, layout.marginLeft, 0f)
    }

    @Test
    fun zoomIsClamped() {
        assertEquals(0.5f, PageLayout.clampZoom(0.1f), 0f)
        assertEquals(6f, PageLayout.clampZoom(60f), 0f)
        assertEquals(1f, PageLayout.clampZoom(Float.NaN), 0f)
    }
}
