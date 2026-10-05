package com.studynotes.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RenderGeometryTest {

    private val letter = PageDimensions(612, 792)

    @Test
    fun pageHeightFollowsTheAspectRatioRounded() {
        assertEquals(PixelSize(1000, 1294), RenderGeometry.pageBitmapSize(1000, letter)) // 1294.12
        assertEquals(PixelSize(1001, 1295), RenderGeometry.pageBitmapSize(1001, letter)) // 1295.41
        assertEquals(PixelSize(612, 792), RenderGeometry.pageBitmapSize(612, letter))
        assertEquals(PixelSize(1000, 773), RenderGeometry.pageBitmapSize(1000, PageDimensions(792, 612))) // 772.73
    }

    @Test
    fun pageHeightIsAtLeastOnePixel() {
        assertEquals(PixelSize(10, 1), RenderGeometry.pageBitmapSize(10, PageDimensions(1000, 1)))
        assertEquals(PixelSize(1, 1), RenderGeometry.pageBitmapSize(1, PageDimensions(5000, 2)))
    }

    @Test
    fun regionSizeRoundsUp() {
        assertEquals(PixelSize(201, 101), RenderGeometry.regionBitmapSize(0f, 0f, 100.2f, 50.5f, 2f, letter))
        assertEquals(PixelSize(200, 100), RenderGeometry.regionBitmapSize(10f, 20f, 110f, 70f, 2f, letter))
        assertEquals(PixelSize(1, 1), RenderGeometry.regionBitmapSize(10f, 10f, 10.001f, 10.001f, 1f, letter))
    }

    @Test
    fun floatNoiseDoesNotAddAPixel() {
        // Tiles requested as tilePx / scale must come back exactly tilePx wide so pooled bitmaps fit.
        val poster = PageDimensions(5000, 5000)
        for (scale in listOf(1.4142135f, 2f, 2.3456f, 2.828427f, 4f, 5.656854f, 8.485281f)) {
            for (col in 0 until 12) {
                val left = col * 512 / scale
                val right = (col + 1) * 512 / scale
                val size = RenderGeometry.regionBitmapSize(left, left, right, right, scale, poster)
                assertEquals("scale $scale col $col", PixelSize(512, 512), size)
            }
        }
    }

    @Test
    fun regionPartlyOffThePageKeepsTheRequestedSize() {
        assertEquals(PixelSize(200, 200), RenderGeometry.regionBitmapSize(500f, 700f, 700f, 900f, 1f, letter))
        assertEquals(PixelSize(100, 100), RenderGeometry.regionBitmapSize(-50f, -50f, 50f, 50f, 1f, letter))
    }

    @Test
    fun emptyOrDisjointRegionsHaveNoSize() {
        assertNull(RenderGeometry.regionBitmapSize(10f, 10f, 10f, 50f, 2f, letter)) // zero width
        assertNull(RenderGeometry.regionBitmapSize(10f, 50f, 60f, 10f, 2f, letter)) // inverted
        assertNull(RenderGeometry.regionBitmapSize(Float.NaN, 0f, 10f, 10f, 2f, letter))
        assertNull(RenderGeometry.regionBitmapSize(0f, 0f, Float.POSITIVE_INFINITY, 10f, 2f, letter))
        assertNull(RenderGeometry.regionBitmapSize(612f, 0f, 700f, 100f, 2f, letter)) // right of the page
        assertNull(RenderGeometry.regionBitmapSize(0f, 792f, 100f, 900f, 2f, letter)) // below the page
        assertNull(RenderGeometry.regionBitmapSize(-100f, 0f, 0f, 100f, 2f, letter)) // left of the page
        assertNull(RenderGeometry.regionBitmapSize(0f, -100f, 100f, 0f, 2f, letter)) // above the page
    }

    @Test
    fun selectionCoordinatesAreRoundedAndClamped() {
        assertEquals(100, RenderGeometry.selectionCoordinate(100.4f, 612))
        assertEquals(101, RenderGeometry.selectionCoordinate(100.5f, 612))
        assertEquals(0, RenderGeometry.selectionCoordinate(-12f, 612))
        assertEquals(612, RenderGeometry.selectionCoordinate(640f, 612))
        assertNull(RenderGeometry.selectionCoordinate(Float.NaN, 612))
        assertNull(RenderGeometry.selectionCoordinate(Float.NEGATIVE_INFINITY, 612))
    }
}
