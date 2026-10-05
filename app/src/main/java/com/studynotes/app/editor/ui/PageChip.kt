package com.studynotes.app.editor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Small "12 / 48" indicator. Appears whenever the current page changes (and when the document
 * opens) and fades out [VISIBLE_MS] after the last change. Tapping it opens "Go to page".
 */
@Composable
fun PageChip(pageIndex: Int, pageCount: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(pageIndex) {
        visible = true
        delay(VISIBLE_MS)
        visible = false
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(400)),
    ) {
        Surface(
            onClick = onClick,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.82f),
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            modifier = Modifier.semantics { contentDescription = "Page ${pageIndex + 1} of $pageCount. Go to page" },
        ) {
            Text(
                text = pageLabel(pageIndex, pageCount),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            )
        }
    }
}

private const val VISIBLE_MS = 1_500L

/**
 * Shown while notes are hidden (⋮ → Hide notes), so hidden notes don't look lost. Tapping it shows
 * them again.
 */
@Composable
fun NotesHiddenChip(visible: Boolean, onShow: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(200)),
    ) {
        Surface(
            onClick = onShow,
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            shadowElevation = 2.dp,
            modifier = Modifier.semantics { contentDescription = "Notes are hidden. Show notes" },
        ) {
            Row(
                modifier = Modifier.padding(start = 12.dp, end = 14.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Notes hidden · Show", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
