package com.studynotes.app.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.studynotes.app.model.Palette
import com.studynotes.app.model.Tool
import com.studynotes.app.model.ToolState
import com.studynotes.app.ui.icons.ToolIcons

/**
 * The floating tool selector. Tapping the already-selected Highlighter, Pen or Note button opens a
 * compact options popover (colours, widths, highlighter switches) above the pill. A selected tool
 * that has options shows a small chevron and announces "Show colours and sizes" to TalkBack.
 */
@Composable
fun ToolPill(
    toolState: ToolState,
    supportsTextSelection: Boolean,
    onSelectTool: (Tool) -> Unit,
    onUpdateTools: ((ToolState) -> ToolState) -> Unit,
    modifier: Modifier = Modifier,
) {
    var optionsFor by remember { mutableStateOf<Tool?>(null) }

    Box(modifier) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier
                    .padding(horizontal = 6.dp, vertical = 4.dp)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                for (tool in Tool.entries) {
                    val selected = toolState.tool == tool
                    ToolButton(
                        icon = tool.icon,
                        label = tool.label,
                        selected = selected,
                        opensOptions = selected && tool.hasOptions,
                        optionsOpen = optionsFor == tool,
                        dotColor = when (tool) {
                            Tool.HIGHLIGHTER -> Color(toolState.highlighterColor)
                            Tool.PEN -> Color(toolState.penColor)
                            Tool.NOTE -> Color(toolState.noteColor)
                            else -> null
                        },
                        onClick = {
                            if (toolState.tool == tool && tool.hasOptions) {
                                optionsFor = if (optionsFor == tool) null else tool
                            } else {
                                optionsFor = null
                                onSelectTool(tool)
                            }
                        },
                    )
                }
            }
        }

        val shownFor = optionsFor
        if (shownFor != null && shownFor == toolState.tool) {
            val density = LocalDensity.current
            val positionProvider = remember(density) {
                with(density) { AboveAnchorPositionProvider(gapPx = 10.dp.roundToPx(), marginPx = 8.dp.roundToPx()) }
            }
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = { optionsFor = null },
                properties = PopupProperties(focusable = true),
            ) {
                val visible = remember { MutableTransitionState(false).apply { targetState = true } }
                AnimatedVisibility(
                    visibleState = visible,
                    enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f, transformOrigin = TransformOrigin(0.5f, 1f)),
                    exit = fadeOut(tween(100)) + scaleOut(targetScale = 0.95f),
                ) {
                    ToolOptionsCard(shownFor, toolState, supportsTextSelection, onUpdateTools)
                }
            }
        }
    }
}

/**
 * One tool. [opensOptions]: this (selected) tool opens its options when tapped again, shown with a
 * chevron (pointing down while [optionsOpen]) and an accessibility click label.
 */
@Composable
private fun ToolButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    opensOptions: Boolean,
    optionsOpen: Boolean,
    dotColor: Color?,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val background by animateColorAsState(
        if (selected) scheme.secondaryContainer else Color.Transparent,
        animationSpec = tween(160),
        label = "toolBackground",
    )
    Tooltipped(label, above = true) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = 24.dp),
                    onClickLabel = when {
                        !opensOptions -> null
                        optionsOpen -> "Hide colours and sizes"
                        else -> "Show colours and sizes"
                    },
                    role = Role.RadioButton,
                    onClick = onClick,
                )
                .semantics {
                    contentDescription = label
                    this.selected = selected
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(44.dp).background(background, CircleShape))
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
            )
            if (opensOptions) {
                Icon(
                    imageVector = if (optionsOpen) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = null,
                    tint = scheme.onSecondaryContainer,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .offset(y = (-1).dp)
                        .size(14.dp),
                )
            }
            if (dotColor != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-8).dp, y = (-8).dp)
                        .size(11.dp)
                        .background(scheme.surfaceContainer, CircleShape)
                        .padding(1.5.dp)
                        .background(dotColor, CircleShape)
                        // Theme outline: a black ink dot must stay visible on the dark pill too.
                        .border(1.dp, swatchEdge(), CircleShape),
                )
            }
        }
    }
}

@Composable
private fun ToolOptionsCard(
    tool: Tool,
    toolState: ToolState,
    supportsTextSelection: Boolean,
    onUpdateTools: ((ToolState) -> ToolState) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .width(IntrinsicOptionsWidth)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = tool.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, bottom = 2.dp),
            )
            when (tool) {
                Tool.HIGHLIGHTER -> {
                    ColorSwatchRow(Palette.HIGHLIGHTER_COLORS, toolState.highlighterColor, { c ->
                        onUpdateTools { it.copy(highlighterColor = c) }
                    })
                    WidthRow(Palette.HIGHLIGHTER_WIDTHS, toolState.highlighterWidth, toolState.highlighterColor, { w ->
                        onUpdateTools { it.copy(highlighterWidth = w) }
                    })
                    if (supportsTextSelection) {
                        OptionSwitch("Snap to text", toolState.snapToText) { v -> onUpdateTools { it.copy(snapToText = v) } }
                    }
                    OptionSwitch("Straight lines", toolState.straightHighlighter) { v ->
                        onUpdateTools { it.copy(straightHighlighter = v) }
                    }
                }
                Tool.PEN -> {
                    ColorSwatchRow(Palette.PEN_COLORS, toolState.penColor, { c -> onUpdateTools { it.copy(penColor = c) } })
                    WidthRow(Palette.PEN_WIDTHS, toolState.penWidth, toolState.penColor, { w ->
                        onUpdateTools { it.copy(penWidth = w) }
                    })
                }
                Tool.NOTE -> {
                    ColorSwatchRow(Palette.NOTE_COLORS, toolState.noteColor, { c -> onUpdateTools { it.copy(noteColor = c) } })
                }
                Tool.VIEW, Tool.ERASER -> Unit
            }
        }
    }
}

@Composable
private fun OptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Fits six 48 dp swatches with 2 dp gaps plus padding (6x48 + 5x2 + 24 = 322); also holds the switch rows. */
private val IntrinsicOptionsWidth = 324.dp

/** Places a popup centred above its anchor, kept inside the window. */
private class AboveAnchorPositionProvider(private val gapPx: Int, private val marginPx: Int) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val x = (anchorBounds.center.x - popupContentSize.width / 2).coerceIn(marginPx, maxX)
        val y = (anchorBounds.top - popupContentSize.height - gapPx).coerceAtLeast(marginPx)
        return IntOffset(x, y)
    }
}

private val Tool.hasOptions: Boolean get() = this == Tool.HIGHLIGHTER || this == Tool.PEN || this == Tool.NOTE

private val Tool.label: String
    get() = when (this) {
        Tool.VIEW -> "View"
        Tool.HIGHLIGHTER -> "Highlighter"
        Tool.PEN -> "Pen"
        Tool.NOTE -> "Note"
        Tool.ERASER -> "Eraser"
    }

private val Tool.icon: ImageVector
    get() = when (this) {
        Tool.VIEW -> ToolIcons.Hand
        Tool.HIGHLIGHTER -> ToolIcons.Highlighter
        Tool.PEN -> ToolIcons.Pen
        Tool.NOTE -> ToolIcons.StickyNote
        Tool.ERASER -> ToolIcons.Eraser
    }
