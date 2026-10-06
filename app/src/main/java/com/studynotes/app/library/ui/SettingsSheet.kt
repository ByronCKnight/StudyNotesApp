package com.studynotes.app.library.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.studynotes.app.BuildConfig
import com.studynotes.app.Publisher
import com.studynotes.app.data.Settings

/** Settings bottom sheet: library folder, pen & touch, autosave interval, version and publisher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    displayPath: String,
    isPublic: Boolean,
    hasFolderAccess: Boolean,
    drawWithFinger: Boolean,
    autosaveMinutes: Int,
    onAllowFolderAccess: () -> Unit,
    onDrawWithFingerChange: (Boolean) -> Unit,
    onAutosaveMinutesChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetMaxWidth = SheetMaxWidth,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)

            Section("Library folder") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Column(Modifier.weight(1f)) {
                        Text(displayPath, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = folderDescription(isPublic, hasFolderAccess),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!hasFolderAccess) {
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = onAllowFolderAccess, modifier = Modifier.padding(start = 40.dp)) {
                        Text("Allow folder access")
                    }
                }
            }

            Section("Pen & touch") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = drawWithFinger,
                            role = Role.Switch,
                            onValueChange = onDrawWithFingerChange,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Draw with finger", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "When off, only a stylus draws; fingers scroll and zoom.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The row handles the toggle so the whole line is one touch target.
                    Switch(checked = drawWithFinger, onCheckedChange = null)
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Lenovo Tab Pen Plus: keep Bluetooth on so the pen works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Section("Autosave") {
                Text(
                    text = "Unsaved highlights and notes are saved every:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                val choices = Settings.AUTOSAVE_CHOICES
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    choices.forEachIndexed { index, minutes ->
                        SegmentedButton(
                            selected = minutes == autosaveMinutes,
                            onClick = { onAutosaveMinutesChange(minutes) },
                            shape = SegmentedButtonDefaults.itemShape(index, choices.size),
                        ) {
                            Text("$minutes min", maxLines = 1)
                        }
                    }
                }
            }

            Section("About") {
                AboutPublisher()
            }
        }
    }
}

private fun folderDescription(isPublic: Boolean, hasFolderAccess: Boolean): String = when {
    isPublic && hasFolderAccess -> "Visible in the Files app and over USB from a computer."
    isPublic -> "Files added by a computer or another app stay hidden until you allow access."
    else -> "Only StudyNotes can see these files."
}

/**
 * Version, publisher and copyright notice. The logo, website and support address appear only once
 * they are filled in on [Publisher].
 */
@Composable
private fun AboutPublisher() {
    val uriHandler = LocalUriHandler.current
    Publisher.logo?.let { logo ->
        Image(
            painter = painterResource(logo),
            contentDescription = Publisher.NAME,
            modifier = Modifier
                .heightIn(max = 48.dp)
                .padding(bottom = 12.dp),
        )
    }
    Text("StudyNotes ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyLarge)
    Text(
        text = "Developed by ${Publisher.NAME}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "${Publisher.copyright()}. ${Publisher.RIGHTS}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val links = listOfNotNull(
        Publisher.website?.let { "Website" to it },
        Publisher.supportEmail?.let { "Contact" to "mailto:$it" },
    )
    if (links.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            links.forEach { (label, uri) ->
                TextButton(onClick = { uriHandler.openUri(uri) }) { Text(label) }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Spacer(Modifier.height(28.dp))
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 12.dp),
    )
    Column(content = content)
}
