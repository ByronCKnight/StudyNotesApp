package com.studynotes.app.model

/** Editor tools, in toolbar order. */
enum class Tool { VIEW, HIGHLIGHTER, PEN, NOTE, ERASER }

/** Everything the canvas needs to know about the active tool. Widths are in page points. */
data class ToolState(
    val tool: Tool = Tool.HIGHLIGHTER,
    val highlighterColor: Int = Palette.HIGHLIGHTER_COLORS[0],
    val highlighterWidth: Float = Palette.HIGHLIGHTER_WIDTHS[1],
    val penColor: Int = Palette.PEN_COLORS[0],
    val penWidth: Float = Palette.PEN_WIDTHS[1],
    val noteColor: Int = Palette.NOTE_COLORS[0],
    /** Snap highlighter strokes to the text lines they cover (only where the platform supports text selection). */
    val snapToText: Boolean = false,
    /** Highlighter always draws straight lines (ruler mode). */
    val straightHighlighter: Boolean = false,
)

object Palette {
    /** Opaque colours; highlighters are drawn with MULTIPLY so these read as translucent ink. */
    val HIGHLIGHTER_COLORS: List<Int> = listOf(
        0xFFFFE94D.toInt(), // yellow
        0xFFA8F0A0.toInt(), // green
        0xFF9FD8FF.toInt(), // blue
        0xFFFFAACF.toInt(), // pink
        0xFFFFC36B.toInt(), // orange
        0xFFD3B8FF.toInt(), // purple
    )
    val HIGHLIGHTER_WIDTHS: List<Float> = listOf(8f, 14f, 22f)

    val PEN_COLORS: List<Int> = listOf(
        0xFF1F1F24.toInt(), // ink black
        0xFF2457D6.toInt(), // blue
        0xFFD93636.toInt(), // red
        0xFF1E8C4E.toInt(), // green
    )
    val PEN_WIDTHS: List<Float> = listOf(1.0f, 2.0f, 3.5f)

    val NOTE_COLORS: List<Int> = listOf(
        0xFFFFF3A6.toInt(), // yellow
        0xFFCFE7FF.toInt(), // blue
        0xFFD6F5CF.toInt(), // green
        0xFFFFD9E8.toInt(), // pink
    )
}
