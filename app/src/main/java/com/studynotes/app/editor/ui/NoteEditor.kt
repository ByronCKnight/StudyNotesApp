package com.studynotes.app.editor.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.studynotes.app.editor.NoteEditorRequest
import com.studynotes.app.model.Palette
import kotlinx.coroutines.launch

/**
 * Edits the text and colour of a sticky note: a bottom sheet on phones, a dialog on wide screens.
 *
 * - Done with text saves ([onDone]); Done on an emptied existing note asks whether to delete it.
 * - Delete (existing notes) calls [onDelete]; undo can restore it.
 * - Dismissing (swipe down, tap outside, back) keeps what was typed, like Done, except that it never
 *   deletes: an empty result just cancels ([onCancel]).
 * - Every change of text or colour is reported through [onDraftChange], so the screen can keep an
 *   unfinished note when the app goes to the background.
 *
 * The keyboard never covers the colours or Done: the dialog lays itself out above the keyboard and
 * the text area scrolls when space runs short. Short windows (landscape phones) get the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditor(
    request: NoteEditorRequest,
    onDone: (text: String, color: Int) -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
    onDraftChange: (text: String, color: Int) -> Unit = { _, _ -> },
) {
    val note = request.note
    var value by rememberSaveable(note.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(note.text, selection = TextRange(note.text.length)))
    }
    var color by rememberSaveable(note.id) { mutableIntStateOf(note.color) }
    var confirmDelete by rememberSaveable(note.id) { mutableStateOf(false) }

    val dismiss = {
        if (value.text.isBlank()) onCancel() else onDone(value.text, color)
    }
    val onValueChange = { next: TextFieldValue ->
        if (next.text != value.text) onDraftChange(next.text, color)
        value = next
    }
    val onColorChange = { next: Int ->
        color = next
        onDraftChange(value.text, next)
    }
    val containerSize = LocalWindowInfo.current.containerSize
    val (wide, short) = with(LocalDensity.current) {
        val width = containerSize.width.toDp()
        val height = containerSize.height.toDp()
        (width >= WideScreenWidth && height >= WideScreenHeight) to (height < ShortScreenHeight)
    }

    if (short && !wide) {
        // A phone in landscape: with the keyboard up only a strip of screen is left, too little for
        // a bottom sheet. A full-window card gives the text all the height the keyboard leaves.
        Dialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    // The full-size box counts as dialog content, so outside taps are handled here.
                    .pointerInput(Unit) { detectTapGestures { dismiss() } }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    NoteEditorContent(
                        isNew = request.isNew,
                        compact = true,
                        value = value,
                        onValueChange = onValueChange,
                        color = color,
                        onColorChange = onColorChange,
                        onDelete = onDelete,
                        onDone = { if (value.text.isBlank() && !request.isNew) confirmDelete = true else dismiss() },
                    )
                }
            }
        }
    } else if (wide) {
        // Edge-to-edge dialog window: the content handles the insets (the keyboard included), so the
        // card always sits above the keyboard instead of being panned partly behind it.
        Dialog(
            onDismissRequest = dismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // The full-size box counts as dialog content, so outside taps are handled here.
                    .pointerInput(Unit) { detectTapGestures { dismiss() } }
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 6.dp,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(0.9f)
                        // Taps on the card itself must not reach the dismissing box behind it.
                        .pointerInput(Unit) { detectTapGestures { } },
                ) {
                    NoteEditorContent(
                        isNew = request.isNew,
                        value = value,
                        onValueChange = onValueChange,
                        color = color,
                        onColorChange = onColorChange,
                        onDelete = onDelete,
                        onDone = { if (value.text.isBlank() && !request.isNew) confirmDelete = true else dismiss() },
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    } else {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val scope = rememberCoroutineScope()
        // Animate the sheet away before acting, so it doesn't vanish abruptly.
        val hideThen = { action: () -> Unit ->
            scope.launch { sheetState.hide() }.invokeOnCompletion { action() }
            Unit
        }
        ModalBottomSheet(onDismissRequest = dismiss, sheetState = sheetState) {
            NoteEditorContent(
                isNew = request.isNew,
                value = value,
                onValueChange = onValueChange,
                color = color,
                onColorChange = onColorChange,
                onDelete = { hideThen(onDelete) },
                onDone = {
                    if (value.text.isBlank() && !request.isNew) confirmDelete = true else hideThen(dismiss)
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this note?") },
            text = { Text("The note is empty.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun NoteEditorContent(
    isNew: Boolean,
    compact: Boolean = false,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    color: Int,
    onColorChange: (Int) -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
    val noteColor = Color(color)
    if (compact) {
        // A landscape phone with the keyboard up leaves little more than 100 dp of height: the text
        // gets all of it (no outlined field, whose padding alone would take a third) and scrolls
        // inside itself, keeping the cursor in view; colours and buttons sit beside it.
        val shape = MaterialTheme.shapes.small
        Row(
            modifier = modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = NoteInk),
                cursorBrush = SolidColor(NoteInk),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text("Type your note…", style = MaterialTheme.typography.bodyLarge, color = NotePlaceholder)
                        }
                        inner()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(noteColor, shape)
                    .border(1.dp, MaterialTheme.colorScheme.outline, shape)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .focusRequester(focus),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorSwatchRow(Palette.NOTE_COLORS, color, onColorChange)
                if (!isNew) {
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = "Delete note", tint = MaterialTheme.colorScheme.error)
                    }
                }
                Button(onClick = onDone) { Text("Done") }
            }
        }
        return
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(if (isNew) "New note" else "Edit note", style = MaterialTheme.typography.titleLarge)
        // Only the text area gives way when space is short (keyboard up, short window); the title,
        // colours and buttons stay visible.
        Column(
            Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                minLines = 4,
                maxLines = 12,
                placeholder = { Text("Type your note…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                // Preview the card: the note's colour with the dark text the page will show.
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = noteColor,
                    unfocusedContainerColor = noteColor,
                    focusedTextColor = NoteInk,
                    unfocusedTextColor = NoteInk,
                    cursorColor = NoteInk,
                    focusedPlaceholderColor = NotePlaceholder,
                    unfocusedPlaceholderColor = NotePlaceholder,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus),
            )
        }
        ColorSwatchRow(Palette.NOTE_COLORS, color, onColorChange)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNew) {
                TextButton(
                    onClick = onDelete,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Delete")
                }
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = onDone) { Text("Done") }
        }
    }
}

/** From this window size up the editor is a centred dialog instead of a bottom sheet. */
private val WideScreenWidth = 600.dp
private val WideScreenHeight = 600.dp

/** Below this window height (a phone in landscape) the editor is a full-window card. */
private val ShortScreenHeight = 480.dp

/** Matches the note text colour drawn on the page. */
private val NoteInk = Color(0xFF1F1F24)
private val NotePlaceholder = Color(0xFF1F1F24).copy(alpha = 0.45f)
