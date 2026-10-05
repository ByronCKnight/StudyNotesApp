package com.studynotes.app.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.group
import androidx.compose.ui.unit.dp

/**
 * Outline tool icons drawn for StudyNotes: 24 dp, 24-unit viewport, 2-unit round-capped strokes, to
 * sit naturally next to Material Symbols Rounded. The vectors are drawn in black; `Icon` tints them.
 */
object ToolIcons {

    /** Chisel-tip highlighter marker, tilted, above a short line of ink. */
    val Highlighter: ImageVector by lazy {
        strokeIcon("Highlighter") {
            group(rotate = 45f, pivotX = 12f, pivotY = 11f) {
                strokePath("M8 11.5V4.5a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v7z")
                strokePath("M8 11.5l1.5 2.5v4.5l5-2.5v-2l1.5-2.5")
            }
            strokePath("M3 21.5h7")
        }
    }

    /** Slim ballpoint pen with a cap band. */
    val Pen: ImageVector by lazy {
        strokeIcon("Pen") {
            strokePath("M15.2 5.3l3.5 3.5")
            strokePath("M17.4 3.1a2.47 2.47 0 0 1 3.5 3.5L8.5 19l-4.8 1.3L5 15.5z")
        }
    }

    /** Tilted block eraser resting on a line. */
    val Eraser: ImageVector by lazy {
        strokeIcon("Eraser") {
            group(rotate = -45f, pivotX = 12f, pivotY = 12f) {
                strokePath("M5 8.5h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2z")
                strokePath("M10 8.5v7")
            }
            strokePath("M9.5 21h11")
        }
    }

    /** Sticky note with a folded corner and two lines of text. */
    val StickyNote: ImageVector by lazy {
        strokeIcon("StickyNote") {
            strokePath("M15 20H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v9z")
            strokePath("M15 20v-3a2 2 0 0 1 2-2h3")
            strokePath("M8 9h8")
            strokePath("M8 12.5h5")
        }
    }

    /** Open hand: the View (scroll / pan) tool. */
    val Hand: ImageVector by lazy {
        strokeIcon("Hand") {
            strokePath("M8 13V6a1.5 1.5 0 0 1 3 0v5")
            strokePath("M11 11V4.5a1.5 1.5 0 0 1 3 0V11")
            strokePath("M14 11V5.5a1.5 1.5 0 0 1 3 0v6")
            strokePath(
                "M17 11.5V8a1.5 1.5 0 0 1 3 0v6a7 7 0 0 1-7 7h-1.2c-2 0-3.3-.6-4.6-1.9" +
                    "L3.6 15.4a1.6 1.6 0 0 1 2.3-2.2L8 15.3V13",
            )
        }
    }

    private inline fun strokeIcon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private fun ImageVector.Builder.strokePath(pathData: String) {
        addPath(
            pathData = addPathNodes(pathData),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
}
