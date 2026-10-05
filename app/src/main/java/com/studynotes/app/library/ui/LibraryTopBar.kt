package com.studynotes.app.library.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studynotes.app.data.SortOrder

/** Menu label of a sort order. */
val SortOrder.label: String
    get() = when (this) {
        SortOrder.RECENT -> "Recent"
        SortOrder.NAME -> "Name"
        SortOrder.DATE_ADDED -> "Date added"
    }

/**
 * The library's top bar. Normally a collapsing "Library" bar with search, sort and settings; in
 * search mode it turns into a single-row bar holding an inline search field (back closes it).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTopBar(
    searchActive: Boolean,
    query: String,
    sortOrder: SortOrder,
    /** Extra start padding for the title so it lines up with content that uses wider margins. */
    titleStartPadding: Dp,
    scrollBehavior: TopAppBarScrollBehavior,
    onSearchActiveChange: (Boolean) -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (SortOrder) -> Unit,
    onOpenSettings: () -> Unit,
) {
    BackHandler(enabled = searchActive) { onSearchActiveChange(false) }
    AnimatedContent(
        targetState = searchActive,
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
        },
        label = "topBar",
    ) { searching ->
        if (searching) {
            TopAppBar(
                navigationIcon = {
                    TooltipIconButton(
                        icon = Icons.AutoMirrored.Rounded.ArrowBack,
                        label = "Close search",
                        onClick = { onSearchActiveChange(false) },
                    )
                },
                title = { SearchField(query = query, onQueryChange = onQueryChange) },
            )
        } else {
            MediumTopAppBar(
                title = { Text("Library", Modifier.padding(start = titleStartPadding)) },
                actions = {
                    TooltipIconButton(
                        icon = Icons.Rounded.Search,
                        label = "Search",
                        onClick = { onSearchActiveChange(true) },
                    )
                    SortButton(sortOrder = sortOrder, onSortChange = onSortChange)
                    TooltipIconButton(
                        icon = Icons.Rounded.Settings,
                        label = "Settings",
                        onClick = onOpenSettings,
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        }
    }
}

@Composable
private fun SortButton(sortOrder: SortOrder, onSortChange: (SortOrder) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            icon = Icons.AutoMirrored.Rounded.Sort,
            label = "Sort by",
            onClick = { open = true },
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(
                text = "Sort by",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
            )
            SortOrder.entries.forEach { order ->
                val selected = order == sortOrder
                DropdownMenuItem(
                    text = {
                        Text(
                            text = order.label,
                            fontWeight = if (selected) FontWeight.SemiBold else null,
                        )
                    },
                    onClick = {
                        open = false
                        onSortChange(order)
                    },
                    leadingIcon = {
                        if (selected) {
                            Icon(Icons.Rounded.Check, contentDescription = "Selected")
                        } else {
                            Spacer(Modifier.size(24.dp))
                        }
                    },
                    modifier = Modifier.width(200.dp),
                )
            }
        }
    }
}

/** Pill-shaped search field that takes focus (and the keyboard) when it appears empty. */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusRequester) {
        // Returning to an existing search (e.g. back from a document) shouldn't pop the keyboard.
        if (query.isEmpty()) focusRequester.requestFocus()
    }
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(end = 12.dp)
            .focusRequester(focusRequester),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Search,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(
                            text = "Search your library",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
                if (query.isNotEmpty()) {
                    TooltipIconButton(
                        icon = Icons.Rounded.Close,
                        label = "Clear search",
                        onClick = {
                            onQueryChange("")
                            focusRequester.requestFocus()
                        },
                    )
                } else {
                    Spacer(Modifier.width(12.dp))
                }
            }
        },
    )
}
