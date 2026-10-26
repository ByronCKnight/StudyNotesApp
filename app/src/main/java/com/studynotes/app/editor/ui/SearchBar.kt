package com.studynotes.app.editor.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** What the find bar shows. */
data class SearchBarState(
    val query: String,
    val hitCount: Int,
    /** 0-based index of the hit being shown, -1 for none. */
    val current: Int,
    /** Pages are still being searched. */
    val searching: Boolean,
)

/** Actions of the find bar. */
interface SearchBarActions {
    fun onQueryChange(query: String)
    fun onNext()
    fun onPrevious()
    fun onClose()

    /** The field gained or lost keyboard focus; the editor's shortcuts stand back while it has it. */
    fun onFocusChange(focused: Boolean)
}

/**
 * Find-in-document row under the editor's top bar: a pill search field that takes the keyboard
 * when it appears, the result count ("3 of 12", "No matches", a spinner while pages are still
 * being searched) and Previous / Next / Close. Enter (or the keyboard's Search) goes to the next
 * hit, Shift+Enter to the previous one, Escape and Back close the bar.
 */
@Composable
fun DocumentSearchBar(state: SearchBarState, actions: SearchBarActions, modifier: Modifier = Modifier) {
    BackHandler(onBack = actions::onClose)
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = state.query,
            onValueChange = actions::onQueryChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { actions.onNext() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester)
                .onFocusChanged { actions.onFocusChange(it.isFocused) }
                .onPreviewKeyEvent { event -> handleSearchFieldKey(event, actions) }
                .semantics { contentDescription = "Find in document" },
            decorationBox = { innerTextField ->
                Row(
                    modifier = Modifier
                        .height(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(start = 14.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.query.isEmpty()) {
                            Text(
                                text = "Find in document",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        innerTextField()
                    }
                    if (state.query.isNotEmpty()) {
                        Tooltipped("Clear") {
                            IconButton(
                                onClick = {
                                    actions.onQueryChange("")
                                    focusRequester.requestFocus()
                                },
                                modifier = Modifier.size(36.dp),
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                            }
                        }
                    } else {
                        Spacer(Modifier.width(10.dp))
                    }
                }
            },
        )
        SearchCount(state)
        BarIconButton(Icons.Rounded.KeyboardArrowUp, "Previous match", enabled = state.hitCount > 0, onClick = actions::onPrevious)
        BarIconButton(Icons.Rounded.KeyboardArrowDown, "Next match", enabled = state.hitCount > 0, onClick = actions::onNext)
        BarIconButton(Icons.Rounded.Close, "Close search", onClick = actions::onClose)
    }
}

/** "3 of 12" / "No matches", with a small spinner while pages are still being searched. */
@Composable
private fun SearchCount(state: SearchBarState) {
    val label = searchCountText(state.query, state.hitCount, state.current, state.searching)
    Row(
        modifier = Modifier.padding(start = 10.dp, end = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.searching && state.query.isNotBlank()) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (label != null) Spacer(Modifier.width(8.dp))
        }
        if (label != null) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The find bar's result text: "3 of 12" while there are hits, "No matches" once the whole
 * document was searched without any, nothing for a blank query or while the first hit is still
 * being looked for (the spinner says so).
 */
internal fun searchCountText(query: String, hitCount: Int, current: Int, searching: Boolean): String? = when {
    query.isBlank() -> null
    hitCount > 0 -> "${current.coerceIn(0, hitCount - 1) + 1} of $hitCount"
    searching -> null
    else -> "No matches"
}

private fun handleSearchFieldKey(event: KeyEvent, actions: SearchBarActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    return when (event.key) {
        Key.Enter, Key.NumPadEnter -> {
            if (event.isShiftPressed) actions.onPrevious() else actions.onNext()
            true
        }
        Key.Escape -> {
            actions.onClose()
            true
        }
        else -> false
    }
}
