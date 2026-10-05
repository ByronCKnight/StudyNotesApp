package com.studynotes.app.export

/** Splits an ARGB colour into PDF DeviceRGB components [r, g, b] in 0..1 (alpha is ignored). */
internal fun rgbComponents(argb: Int): FloatArray = floatArrayOf(
    ((argb ushr 16) and 0xFF) / 255f,
    ((argb ushr 8) and 0xFF) / 255f,
    (argb and 0xFF) / 255f,
)

/**
 * Colour maths for exported highlighter ink.
 *
 * On screen the highlighter is drawn with an opaque colour `c` and a MULTIPLY blend, so on white
 * paper it shows exactly `c` and text underneath stays black. In the PDF it is drawn with
 * /BM /Multiply at opacity [ALPHA] and the compensated colour `c' = (c - (1 - ALPHA)) / ALPHA`:
 * - viewers that support blend modes: white → (1 - α) + α·c' = c, black text stays black;
 * - viewers that ignore /BM (normal compositing): white → the same c, while text shows through a
 *   55 % tint instead of being painted over.
 * Channels darker than 1 - ALPHA can't be reached and are clamped to 0.
 */
internal object HighlightColor {
    /** Constant opacity (/CA and /ca) of exported highlighter ink. */
    const val ALPHA = 0.55f

    /** Compensates one 0..1 channel; the result is clamped to 0..1. */
    fun compensate(channel: Float): Float = ((channel - (1f - ALPHA)) / ALPHA).coerceIn(0f, 1f)

    /** Compensated [r, g, b] (0..1) for an opaque ARGB highlighter colour. */
    fun compensatedRgb(argb: Int): FloatArray {
        val rgb = rgbComponents(argb)
        for (i in rgb.indices) rgb[i] = compensate(rgb[i])
        return rgb
    }
}
