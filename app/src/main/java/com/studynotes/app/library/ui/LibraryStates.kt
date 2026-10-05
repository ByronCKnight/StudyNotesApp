package com.studynotes.app.library.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Shown when the library folder has no documents. */
@Composable
fun EmptyLibrary(
    canCopyIntoFolder: Boolean,
    folderPath: String,
    onImport: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val body = if (canCopyIntoFolder) {
        "Import from your device, Google Drive or OneDrive — or copy files into $folderPath."
    } else {
        "Import PDFs and Word files from your device, Google Drive or OneDrive."
    }
    CenteredMessage(
        icon = Icons.AutoMirrored.Rounded.LibraryBooks,
        title = "No documents yet",
        body = body,
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        Button(
            onClick = onImport,
            contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            modifier = Modifier.height(48.dp),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
            Text("Import")
        }
    }
}

/** Shown when a search matches nothing. */
@Composable
fun NoMatches(
    query: String,
    onClearSearch: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    CenteredMessage(
        icon = Icons.Rounded.SearchOff,
        title = "No matches",
        body = "No document titles contain “${query.trim()}”.",
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        TextButton(onClick = onClearSearch) { Text("Clear search") }
    }
}

@Composable
private fun CenteredMessage(
    icon: ImageVector,
    title: String,
    body: String,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit,
) {
    // Scrollable (small landscape phones) yet vertically centred when it fits.
    BoxWithConstraints(modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = viewportHeight)
                .padding(contentPadding)
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(96.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(44.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 420.dp),
            )
            Spacer(Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Slim banner shown while the app can't see the whole shared folder. The text depends on whether
 * the library already lives in Documents/StudyNotes (only other apps' files are invisible) or in
 * private storage (nothing is visible to the computer or Files app). On narrow screens the action
 * sits under the text so the message isn't squeezed into a thin column.
 */
@Composable
fun FolderAccessBanner(
    isPublic: Boolean,
    onAllow: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = if (isPublic) {
        "Allow folder access to see files you copy into Documents/StudyNotes from a computer or another app."
    } else {
        "Your library is in private storage. Allow folder access to keep it in Documents/StudyNotes."
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= 560.dp
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
                verticalAlignment = if (wide) Alignment.CenterVertically else Alignment.Top,
            ) {
                Icon(
                    imageVector = Icons.Rounded.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(top = if (wide) 0.dp else 14.dp)
                        .size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                if (wide) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(vertical = 8.dp),
                    )
                    TextButton(onClick = onAllow) { Text("Allow access") }
                } else {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                        // Pull the text button left so its label lines up with the message.
                        TextButton(onClick = onAllow, modifier = Modifier.offset(x = (-12).dp)) {
                            Text("Allow access")
                        }
                    }
                }
                TooltipIconButton(icon = Icons.Rounded.Close, label = "Dismiss", onClick = onDismiss)
            }
        }
    }
}
