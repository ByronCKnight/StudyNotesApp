package com.studynotes.app.editor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FindInPage
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.SaveAs
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studynotes.app.ui.theme.MetaTextStyle

/** Everything the top bar shows; grouped so the bar's signature stays readable. */
data class TopBarState(
    val title: String,
    val status: String,
    /** Shows [status] in the error colour (a failed save). */
    val statusIsError: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val dirty: Boolean,
    val saving: Boolean,
    val savedFlash: Boolean,
    val notesVisible: Boolean,
    val drawWithFinger: Boolean,
    /** The document has marks; without any, sharing is just "as PDF". */
    val hasMarks: Boolean = true,
)

/** Actions of the top bar and its overflow menu. */
interface TopBarActions {
    fun onBack()
    fun onUndo()
    fun onRedo()
    fun onSave()
    fun onFind()
    fun onGoToPage()
    fun onToggleNotes()
    fun onShare()
    fun onSaveCopy()
    fun onDrawWithFingerChange(enabled: Boolean)
}

/**
 * Slim translucent bar over the top of the canvas: back, title + save status, find, undo, redo,
 * Save and an overflow menu for the less frequent actions. On narrow windows (phones) Find and
 * Redo move into the overflow menu and Save gets tighter padding, so the title stays readable.
 * While [search] is given, the find bar ([DocumentSearchBar]) unfolds underneath.
 */
@Composable
fun EditorTopBar(
    state: TopBarState,
    actions: TopBarActions,
    modifier: Modifier = Modifier,
    search: SearchBarState? = null,
    searchActions: SearchBarActions? = null,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
        // contentColorFor() can't match a translucent colour, so name the content colour outright.
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 2.dp,
    ) {
        Column(
            Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        ) {
            BoxWithConstraints {
                TopBarRow(state, actions, compact = maxWidth < CompactBarWidth, tiny = maxWidth < TinyBarWidth)
            }
            // The last state is kept (plain holder, no snapshot write during composition) so the
            // row can animate out with its contents.
            val lastSearch = remember { LastSearch() }
            if (search != null) lastSearch.value = search
            val shown = lastSearch.value
            AnimatedVisibility(
                visible = search != null && searchActions != null,
                enter = expandVertically(tween(180)) + fadeIn(tween(180)),
                exit = shrinkVertically(tween(150)) + fadeOut(tween(100)),
            ) {
                if (shown != null && searchActions != null) DocumentSearchBar(shown, searchActions)
            }
        }
    }
}

@Composable
private fun TopBarRow(state: TopBarState, actions: TopBarActions, compact: Boolean, tiny: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BarIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Back", onClick = actions::onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp, end = 8.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = state.status,
                style = MetaTextStyle,
                color = if (state.statusIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (!compact) BarIconButton(Icons.Rounded.Search, "Find in document", onClick = actions::onFind)
        BarIconButton(Icons.AutoMirrored.Rounded.Undo, "Undo", enabled = state.canUndo, onClick = actions::onUndo)
        if (!compact) {
            BarIconButton(Icons.AutoMirrored.Rounded.Redo, "Redo", enabled = state.canRedo, onClick = actions::onRedo)
            Spacer(Modifier.width(4.dp))
        }
        SaveButton(
            dirty = state.dirty,
            saving = state.saving,
            savedFlash = state.savedFlash,
            compact = compact,
            iconOnly = tiny,
            onClick = actions::onSave,
        )
        OverflowMenu(state, actions, compact = compact)
    }
}

/** The find bar state last shown, kept while the bar animates out. */
private class LastSearch {
    var value: SearchBarState? = null
}

/** Below this width the bar drops Find and Redo into the overflow menu. */
private val CompactBarWidth = 480.dp

/** Below this width (small phones) Save shows only its icon, so the title stays readable. */
private val TinyBarWidth = 360.dp

@Composable
internal fun BarIconButton(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Tooltipped(label) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label)
        }
    }
}

/** Filled tonal when there is something to save, muted (but still tappable) otherwise. */
@Composable
private fun SaveButton(
    dirty: Boolean,
    saving: Boolean,
    savedFlash: Boolean,
    compact: Boolean,
    iconOnly: Boolean,
    onClick: () -> Unit,
) {
    val colors = if (dirty || saving) {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    } else {
        ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val phase = when {
        saving -> SavePhase.SAVING
        savedFlash -> SavePhase.DONE
        else -> SavePhase.IDLE
    }
    Tooltipped(if (dirty) "Save changes" else "All changes saved") {
        FilledTonalButton(
            onClick = onClick,
            colors = colors,
            contentPadding = when {
                iconOnly -> PaddingValues(0.dp)
                compact -> PaddingValues(start = 10.dp, end = 12.dp)
                else -> PaddingValues(start = 12.dp, end = 16.dp)
            },
            modifier = Modifier
                .height(40.dp)
                .then(if (iconOnly) Modifier.widthIn(min = 44.dp).semantics { contentDescription = "Save" } else Modifier),
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = { (fadeIn(tween(150)) + scaleIn(initialScale = 0.7f)) togetherWith fadeOut(tween(100)) },
                label = "saveIcon",
            ) { p ->
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (p) {
                        SavePhase.SAVING -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = LocalContentColor.current,
                        )
                        SavePhase.DONE -> Icon(Icons.Rounded.Check, contentDescription = null)
                        SavePhase.IDLE -> Icon(Icons.Rounded.Save, contentDescription = null)
                    }
                }
            }
            if (!iconOnly) {
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
                // Always as wide as "Saved", so the brief flash doesn't shift the title.
                Box {
                    Text("Saved", modifier = Modifier.alpha(0f).clearAndSetSemantics { })
                    Text(if (phase == SavePhase.DONE) "Saved" else "Save")
                }
            }
        }
    }
}

private enum class SavePhase { IDLE, SAVING, DONE }

/** [compact]: the bar has no room for Find and Redo, so the menu carries them. */
@Composable
private fun OverflowMenu(state: TopBarState, actions: TopBarActions, compact: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        BarIconButton(Icons.Rounded.MoreVert, "More options") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (compact) {
                DropdownMenuItem(
                    text = { Text("Redo") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = null) },
                    enabled = state.canRedo,
                    onClick = { open = false; actions.onRedo() },
                )
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                MenuItem("Find in document…", Icons.Rounded.Search) { open = false; actions.onFind() }
            }
            MenuItem("Go to page…", Icons.Rounded.FindInPage) { open = false; actions.onGoToPage() }
            MenuItem(
                if (state.notesVisible) "Hide notes" else "Show notes",
                if (state.notesVisible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
            ) { open = false; actions.onToggleNotes() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            MenuItem(if (state.hasMarks) "Share annotated PDF" else "Share as PDF", Icons.Rounded.Share) {
                open = false
                actions.onShare()
            }
            MenuItem("Save a copy…", Icons.Rounded.SaveAs) { open = false; actions.onSaveCopy() }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            DropdownMenuItem(
                text = { Text("Draw with finger") },
                leadingIcon = { Icon(Icons.Rounded.TouchApp, contentDescription = null) },
                trailingIcon = {
                    // The whole row toggles; the checkbox only displays the state.
                    Checkbox(checked = state.drawWithFinger, onCheckedChange = null)
                },
                onClick = { actions.onDrawWithFingerChange(!state.drawWithFinger) },
                // Announce it as a checkbox with its state, since the Checkbox itself isn't clickable.
                modifier = Modifier.semantics {
                    role = Role.Checkbox
                    toggleableState = ToggleableState(state.drawWithFinger)
                },
            )
        }
    }
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
