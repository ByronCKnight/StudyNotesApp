package com.studynotes.app.editor.ui

import kotlin.math.pow

/**
 * WCAG relative luminance (0 = black, 1 = white) of an opaque ARGB colour. Alpha is ignored.
 * Pure Kotlin, so the swatch logic that depends on it can be unit-tested on the JVM.
 */
internal fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** WCAG contrast ratio between two opaque colours, from 1.0 (identical) to 21.0 (black on white). */
internal fun contrastRatio(a: Int, b: Int): Double {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
}

/**
 * [preferred] when it stands out from [background] (at least [MIN_UI_CONTRAST], the WCAG minimum
 * for UI graphics), otherwise [fallback]. Used so e.g. black ink previews stay visible in dark mode.
 */
internal fun readableOn(preferred: Int, background: Int, fallback: Int): Int =
    if (contrastRatio(preferred, background) >= MIN_UI_CONTRAST) preferred else fallback

internal const val MIN_UI_CONTRAST = 3.0
