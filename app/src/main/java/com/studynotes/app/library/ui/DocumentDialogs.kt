package com.studynotes.app.library.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.studynotes.app.model.LibraryDocument

/**
 * Rename dialog: the current title is pre-selected so typing replaces it, the extension is shown
 * as a fixed suffix, and [error] (e.g. "A document with that name already exists") appears inline.
 */
@Composable
fun RenameDialog(
    document: LibraryDocument,
    error: String?,
    inProgress: Boolean,
    onNameChange: () -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var field by rememberSaveable(document.fileName, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(document.title, selection = TextRange(0, document.title.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
    val canConfirm = field.text.isNotBlank() && !inProgress

    AlertDialog(
        onDismissRequest = { if (!inProgress) onDismiss() },
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = field,
                onValueChange = {
                    if (it.text != field.text) onNameChange()
                    field = it
                },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                suffix = { Text(".${document.type.extension}") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { if (canConfirm) onConfirm(field.text) }),
                // Read-only rather than disabled while saving, so focus and the keyboard stay put.
                readOnly = inProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(field.text) }, enabled = canConfirm) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inProgress) { Text("Cancel") }
        },
    )
}

/**
 * Confirmation before deleting a document together with its highlights and notes. The file itself
 * is removed from [folderPath] (the library folder, e.g. "Documents/StudyNotes"; [isPublic] = the
 * shared folder) with no trash or undo, and the text says so.
 */
@Composable
fun DeleteDialog(
    document: LibraryDocument,
    folderPath: String,
    isPublic: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.DeleteOutline, contentDescription = null) },
        title = { Text("Delete “${document.title}”?") },
        text = { Text(deleteConfirmationText(document.fileName, folderPath, isPublic)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
