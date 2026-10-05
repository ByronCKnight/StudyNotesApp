package com.studynotes.app.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studynotes.app.model.Palette

/** A row of round colour swatches; the selected one gets an accent ring. */
@Composable
internal fun ColorSwatchRow(colors: List<Int>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val edge = swatchEdge()
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (color in colors) {
            val isSelected = color == selected
            SelectableCircle(
                selected = isSelected,
                description = colorName(color),
                onClick = { onSelect(color) },
            ) {
                Box(
                    Modifier
                        .size(if (isSelected) 26.dp else 30.dp)
                        .background(Color(color), CircleShape)
                        .border(1.dp, edge, CircleShape),
                )
            }
        }
    }
}

/**
 * Three stroke-width choices drawn as dots of increasing size in the current ink colour, or in a
 * neutral colour when the ink wouldn't stand out from the card (black ink in dark mode).
 */
@Composable
internal fun WidthRow(widths: List<Float>, selected: Float, color: Int, onSelect: (Float) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val edge = swatchEdge()
    val fallback = scheme.onSurfaceVariant.toArgb()
    Row(
        modifier = modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        widths.forEachIndexed { index, width ->
            val isSelected = width == selected
            val behind = if (isSelected) scheme.secondaryContainer else scheme.surfaceContainerHigh
            val dot = Color(readableOn(color, behind.toArgb(), fallback))
            SelectableCircle(
                selected = isSelected,
                description = WIDTH_NAMES.getOrElse(index) { "Width ${index + 1}" },
                onClick = { onSelect(width) },
                filledSelection = true,
            ) {
                Box(
                    Modifier
                        .size(WIDTH_DOTS.getOrElse(index) { WIDTH_DOTS.last() })
                        .background(dot, CircleShape)
                        .border(1.dp, edge, CircleShape),
                )
            }
        }
    }
}

/**
 * 48 dp round selectable target (the minimum touch size). Selection is shown as an accent ring, or with [filledSelection]
 * as a tonal disc behind the content.
 */
@Composable
private fun SelectableCircle(
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    filledSelection: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(48.dp)
            .selectable(
                selected = selected,
                interactionSource = null,
                indication = ripple(bounded = false, radius = 24.dp),
                role = Role.RadioButton,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            val ring = Modifier.size(38.dp)
            Box(
                if (filledSelection) ring.background(scheme.secondaryContainer, CircleShape)
                else ring.border(2.dp, scheme.primary, CircleShape),
            )
        }
        content()
    }
}

/** Human name of a palette colour, for accessibility. */
internal fun colorName(color: Int): String = COLOR_NAMES[color] ?: "Colour"

/** Outline that separates a swatch from the card in both light and dark mode. */
@Composable
internal fun swatchEdge(): Color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)

private val WIDTH_NAMES = listOf("Thin", "Medium", "Thick")
private val WIDTH_DOTS: List<Dp> = listOf(6.dp, 11.dp, 17.dp)

private val COLOR_NAMES: Map<Int, String> = buildMap {
    listOf("Yellow", "Green", "Blue", "Pink", "Orange", "Purple")
        .forEachIndexed { i, name -> Palette.HIGHLIGHTER_COLORS.getOrNull(i)?.let { put(it, name) } }
    listOf("Black", "Blue", "Red", "Green")
        .forEachIndexed { i, name -> Palette.PEN_COLORS.getOrNull(i)?.let { put(it, name) } }
    listOf("Yellow", "Blue", "Green", "Pink")
        .forEachIndexed { i, name -> Palette.NOTE_COLORS.getOrNull(i)?.let { put(it, name) } }
}
