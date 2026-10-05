package com.studynotes.app.export

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSpaceTransformTest {

    // An offset crop box, 500 x 700 pt, so every rotation exercises both offsets.
    private val llx = 30f
    private val lly = 40f
    private val urx = 530f
    private val ury = 740f

    private fun transform(rotation: Int) = PdfSpaceTransform.displayToPdf(rotation, llx, lly, urx, ury)

    private fun assertMaps(t: PdfSpaceTransform, x: Float, y: Float, expectedX: Float, expectedY: Float) {
        assertEquals("x' of ($x, $y)", expectedX, t.mapX(x, y), EPS)
        assertEquals("y' of ($x, $y)", expectedY, t.mapY(x, y), EPS)
    }

    @Test
    fun rotation0_cornersLandOnCropBoxUpright() {
        val t = transform(0)
        assertEquals(500f, t.pageWidth, EPS)
        assertEquals(700f, t.pageHeight, EPS)
        assertMaps(t, 0f, 0f, llx, ury) // top-left
        assertMaps(t, 500f, 0f, urx, ury) // top-right
        assertMaps(t, 0f, 700f, llx, lly) // bottom-left
        assertMaps(t, 500f, 700f, urx, lly) // bottom-right
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, -1f, llx, ury), t.coefficients, EPS)
    }

    @Test
    fun rotation90_displayTopLeftIsUnrotatedBottomLeft() {
        // /Rotate 90 turns the page clockwise: the unrotated left edge becomes the displayed top.
        val t = transform(90)
        assertEquals(700f, t.pageWidth, EPS)
        assertEquals(500f, t.pageHeight, EPS)
        assertMaps(t, 0f, 0f, llx, lly)
        assertMaps(t, 700f, 0f, llx, ury)
        assertMaps(t, 0f, 500f, urx, lly)
        assertMaps(t, 700f, 500f, urx, ury)
        assertArrayEquals(floatArrayOf(0f, 1f, 1f, 0f, llx, lly), t.coefficients, EPS)
    }

    @Test
    fun rotation180_displayTopLeftIsUnrotatedBottomRight() {
        val t = transform(180)
        assertEquals(500f, t.pageWidth, EPS)
        assertEquals(700f, t.pageHeight, EPS)
        assertMaps(t, 0f, 0f, urx, lly)
        assertMaps(t, 500f, 0f, llx, lly)
        assertMaps(t, 0f, 700f, urx, ury)
        assertMaps(t, 500f, 700f, llx, ury)
        assertArrayEquals(floatArrayOf(-1f, 0f, 0f, 1f, urx, lly), t.coefficients, EPS)
    }

    @Test
    fun rotation270_displayTopLeftIsUnrotatedTopRight() {
        // /Rotate 270 turns the page counter-clockwise: the unrotated right edge becomes the top.
        val t = transform(270)
        assertEquals(700f, t.pageWidth, EPS)
        assertEquals(500f, t.pageHeight, EPS)
        assertMaps(t, 0f, 0f, urx, ury)
        assertMaps(t, 700f, 0f, urx, lly)
        assertMaps(t, 0f, 500f, llx, ury)
        assertMaps(t, 700f, 500f, llx, lly)
        assertArrayEquals(floatArrayOf(0f, -1f, -1f, 0f, urx, ury), t.coefficients, EPS)
    }

    @Test
    fun everyRotationIsAnIsometryWithAMirror() {
        // det = -1: lengths (stroke widths) are preserved and the y-down page space is flipped to y-up.
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val t = transform(rotation)
            assertEquals("det for $rotation", -1f, t.a * t.d - t.b * t.c, EPS)
        }
    }

    @Test
    fun interiorPointKeepsItsDistanceFromTheTopLeftCorner() {
        // A point 10 pt right of and 20 pt below the displayed top-left corner, at 90 degrees:
        // "right" in display runs up the unrotated page, "down" runs to the right.
        val t = transform(90)
        assertMaps(t, 10f, 20f, llx + 20f, lly + 10f)
    }

    @Test
    fun cornersGivenInTheWrongOrderAreNormalised() {
        val t = PdfSpaceTransform.displayToPdf(0, urx, ury, llx, lly)
        assertEquals(transform(0), t)
    }

    @Test
    fun rotationIsNormalisedLikePdfium() {
        assertEquals(0, PdfSpaceTransform.normalizeRotation(0))
        assertEquals(90, PdfSpaceTransform.normalizeRotation(90))
        assertEquals(0, PdfSpaceTransform.normalizeRotation(360))
        assertEquals(90, PdfSpaceTransform.normalizeRotation(450))
        assertEquals(270, PdfSpaceTransform.normalizeRotation(-90))
        assertEquals(180, PdfSpaceTransform.normalizeRotation(-180))
        // Not a multiple of 90: truncated to whole quarter turns.
        assertEquals(90, PdfSpaceTransform.normalizeRotation(135))
        assertEquals(0, PdfSpaceTransform.normalizeRotation(45))
    }

    @Test
    fun mapRectReturnsOrderedUserSpaceBounds() {
        val r = transform(0).mapRect(10f, 20f, 110f, 70f)
        assertArrayEquals(floatArrayOf(llx + 10f, ury - 70f, llx + 110f, ury - 20f), r, EPS)
        val rotated = transform(270).mapRect(10f, 20f, 110f, 70f)
        assertArrayEquals(floatArrayOf(urx - 70f, ury - 110f, urx - 20f, ury - 10f), rotated, EPS)
    }

    @Test
    fun commentIconSitsBesideTheCardsTopRightCorner() {
        val r = transform(0).commentIconRect(cardRight = 200f, cardTop = 100f, size = 18f, gap = 2f)
        // Page-space square (202, 100)-(220, 118).
        assertArrayEquals(floatArrayOf(llx + 202f, ury - 118f, llx + 220f, ury - 100f), r, EPS)
    }

    @Test
    fun commentIconIsKeptOnThePage() {
        for (rotation in intArrayOf(0, 90, 180, 270)) {
            val t = transform(rotation)
            // A card hugging the bottom-right corner of the displayed page.
            val r = t.commentIconRect(cardRight = t.pageWidth, cardTop = t.pageHeight - 5f, size = 18f, gap = 2f)
            assertEquals(18f, r[2] - r[0], EPS)
            assertEquals(18f, r[3] - r[1], EPS)
            assertTrue("inside crop box at $rotation: ${r.toList()}",
                r[0] >= llx - EPS && r[1] >= lly - EPS && r[2] <= urx + EPS && r[3] <= ury + EPS)
        }
    }

    private companion object {
        const val EPS = 1e-4f
    }
}
