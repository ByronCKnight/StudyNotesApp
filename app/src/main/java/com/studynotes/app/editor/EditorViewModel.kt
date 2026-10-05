package com.studynotes.app.editor

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studynotes.app.AppContainer
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.data.SidecarLoad
import com.studynotes.app.data.UnlockedCopies
import com.studynotes.app.docx.DocxConversionException
import com.studynotes.app.editor.canvas.AnnotationPainter
import com.studynotes.app.export.ExportActions
import com.studynotes.app.export.ExportDestinations
import com.studynotes.app.export.ExportException
import com.studynotes.app.export.ExportResult
import com.studynotes.app.export.PdfUnlocker
import com.studynotes.app.model.AnnotationFile
import com.studynotes.app.model.DocType
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.Note
import com.studynotes.app.model.SourceInfo
import com.studynotes.app.model.Stroke
import com.studynotes.app.model.Tool
import com.studynotes.app.model.ToolState
import com.studynotes.app.model.ViewState
import com.studynotes.app.model.newAnnotationId
import com.studynotes.app.pdf.PdfDocumentHandle
import com.studynotes.app.pdf.PdfOpenException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.math.abs

/** Lifecycle of opening the document. */
sealed interface LoadState {
    data class Loading(val message: String) : LoadState

    /** The PDF is encrypted; [error] is shown under the password field after a failed attempt. */
    data class NeedsPassword(val error: String? = null) : LoadState

    data class Ready(val handle: PdfDocumentHandle, val initialViewState: ViewState) : LoadState

    data class Error(val message: String) : LoadState
}

/** What the status line under the title says. */
sealed interface SaveStatus {
    /** Everything is saved; [at] is when the sidecar was last written, null if it never was. */
    data class Saved(val at: Long?) : SaveStatus
    data object Unsaved : SaveStatus
    data object Saving : SaveStatus
    data object Failed : SaveStatus
}

/** The note editor sheet is open for [note] (a not-yet-added note when [isNew]). */
data class NoteEditorRequest(val pageIndex: Int, val note: Note, val isNew: Boolean)

/** One-shot events for the screen. */
sealed interface EditorEvent {
    data class Message(val text: String, val action: MessageAction? = null, val long: Boolean = false) : EditorEvent

    /** Show the system share sheet for an exported PDF. */
    data class Share(val file: File, val title: String) : EditorEvent

    /** Leave the editor (everything that had to be saved is saved). */
    data object Close : EditorEvent
}

enum class MessageAction(val label: String) { RETRY_SAVE("Retry") }

/**
 * State holder for one open document: loading (DOCX conversion, password unlock), annotations with
 * undo/redo, tool state, saving (manual, periodic autosave, on background, on close) and export.
 *
 * All public functions must be called on the main thread; heavy work hops to background dispatchers.
 * [pageSizeCacheDir] is where [PdfDocumentHandle] caches measured page sizes (null = no cache).
 */
class EditorViewModel(
    private val container: AppContainer,
    private val documentFile: File,
    private val pageSizeCacheDir: File? = null,
) : ViewModel() {

    /** Document title shown in the top bar (file name without extension). */
    val title: String = documentFile.nameWithoutExtension
    val docType: DocType? = DocType.fromFileName(documentFile.name)

    private val settings = container.settings

    /**
     * The library this document belongs to, fixed once it is found to be inside it (null before).
     * Every sidecar, rendition and unlocked-copy access uses this, never the live library location,
     * so a migration can't make the editor read or write another document's files.
     */
    @Volatile
    private var docPaths: LibraryPaths? = null

    private val _loadState = MutableStateFlow<LoadState>(LoadState.Loading(OPENING))
    val loadState: StateFlow<LoadState> = _loadState.asStateFlow()

    private var undoStack = UndoStack(DocumentAnnotations.EMPTY)
    private val _annotations = MutableStateFlow(DocumentAnnotations.EMPTY)
    val annotations: StateFlow<DocumentAnnotations> = _annotations.asStateFlow()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    private val _toolState = MutableStateFlow(settings.current.toolState)
    val toolState: StateFlow<ToolState> = _toolState.asStateFlow()

    /** Palm rejection: only a stylus draws. Undecided (null in settings) counts as false. */
    val stylusOnly: StateFlow<Boolean> = settings.state.map { it.stylusOnly == true }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settings.current.stylusOnly == true)

    /** Per document and per session: every document opens with its notes shown. */
    private val _notesVisible = MutableStateFlow(true)
    val notesVisible: StateFlow<Boolean> = _notesVisible.asStateFlow()

    private val _saveStatus = MutableStateFlow<SaveStatus>(SaveStatus.Saved(null))
    val saveStatus: StateFlow<SaveStatus> = _saveStatus.asStateFlow()

    /** True when there are edits that are not in the saved sidecar. */
    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** True for ~1.5 s after a manual save succeeded (the Save button shows a check mark). */
    private val _savedFlash = MutableStateFlow(false)
    val savedFlash: StateFlow<Boolean> = _savedFlash.asStateFlow()

    private val _currentPage = MutableStateFlow(0)
    val currentPage: StateFlow<Int> = _currentPage.asStateFlow()

    private val _noteEditor = MutableStateFlow<NoteEditorRequest?>(null)
    val noteEditor: StateFlow<NoteEditorRequest?> = _noteEditor.asStateFlow()

    private val _exporting = MutableStateFlow(false)
    val exporting: StateFlow<Boolean> = _exporting.asStateFlow()

    private val _events = Channel<EditorEvent>(Channel.BUFFERED)
    val events: Flow<EditorEvent> = _events.receiveAsFlow()

    // Main-thread state.
    private var handle: PdfDocumentHandle? = null
    private var savedVersion = UndoStack.INITIAL_VERSION
    private var lastSavedAt: Long? = null
    private var saving = false
    private var lastSaveFailed = false
    private var discarded = false
    private var cleared = false
    private var toolChosenByUser = false
    private var lastViewState = ViewState()

    /**
     * Held from opening until the final save has landed ([AnnotationStore.reserveSave]): a reopen
     * of this document waits for it, and the library won't re-attach other annotations to it.
     */
    private var session: CompletableDeferred<Unit>? = null

    /**
     * The sidecar on disk couldn't be read or preserved; saving would destroy the only copy of
     * the user's earlier notes, so this editor never writes it.
     */
    private var savesBlocked = false

    /** Position stored in the sidecar; null while the document has no sidecar. */
    private var lastSavedViewState: ViewState? = null
    private var viewStateProvider: (() -> ViewState?)? = null
    private var savedFlashJob: Job? = null
    private var exportJob: Job? = null

    /** Latest text/colour typed in the open note editor, so it survives going to the background. */
    private var noteDraft: NoteDraft? = null

    private val saveMutex = Mutex()

    /** Measures note cards for clamping them onto the page; used on the main thread only. */
    private val notePainter by lazy { AnnotationPainter() }

    /** Fallback for [onBackground]: the screen's Activity-lifecycle hook is the primary trigger. */
    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_STOP) onBackground()
    }

    private enum class SaveReason { MANUAL, AUTO, BACKGROUND, CLOSE }

    private class SaveSnapshot(val annotations: DocumentAnnotations, val version: Long, val savedAt: Long)

    private class NoteDraft(val noteId: String, val text: String, val color: Int)

    /** The document is no longer where it was opened from (moved by a migration, or deleted). */
    private class DocumentMovedException : IOException("Document moved or deleted")

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        viewModelScope.launch { open() }
        viewModelScope.launch { autosaveLoop() }
        // Persist tool changes in order, off the main thread (settings are JSON in SharedPreferences).
        viewModelScope.launch(Dispatchers.IO) {
            _toolState.drop(1).collect { state -> settings.updateToolState { state } }
        }
    }

    // ---------------------------------------------------------------- Opening

    private suspend fun open() {
        try {
            val type = docType ?: return fail("This file type isn't supported.")
            if (!withContext(Dispatchers.IO) { documentFile.isFile }) return fail(MSG_MISSING)
            // Waits for the library location to settle (startup probe / migration), then pins it.
            val paths = container.libraryStorage.withLibraryLock { current ->
                current.takeIf { withContext(Dispatchers.IO) { isDocumentIn(documentFile, it.root) } }
                    // Under the library lock, so no library refresh can move another document's
                    // annotations onto this one between here and the load (this editor would then
                    // save over them). It also makes a reopen of this document wait for our saves.
                    ?.also(::beginSession)
            } ?: return fail(MSG_MOVED)
            docPaths = paths
            var sourceChanged = false
            val pdf = when (type) {
                DocType.PDF -> documentFile
                DocType.DOCX -> {
                    // Checked on every open, not only when converting here: the library's thumbnail
                    // loader may already have regenerated a stale rendition in the background, which
                    // leaves an up-to-date cached rendition whose change is still unacknowledged.
                    sourceChanged = withContext(Dispatchers.IO) {
                        runCatching { container.renditions.isSourceChanged(paths, documentFile) }.getOrDefault(false)
                    }
                    val cached = withContext(Dispatchers.IO) {
                        runCatching { container.renditions.cachedRendition(paths, documentFile) }.getOrNull()
                    }
                    cached ?: run {
                        _loadState.value = LoadState.Loading("Preparing document…")
                        try {
                            container.renditions.ensureRendition(paths, documentFile)
                        } catch (e: DocxConversionException) {
                            return fail(e.message ?: "This Word document couldn't be opened.")
                        }
                    }
                }
            }
            val opened = try {
                openHandle(pdf)
            } catch (e: PdfOpenException) {
                if (e.reason == PdfOpenException.Reason.PASSWORD_PROTECTED && type == DocType.PDF) {
                    openUnlockedCopyOrAskPassword()
                    return
                }
                return fail(openErrorMessage(e))
            }
            finishOpening(opened, sourceChanged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Opening ${documentFile.name} failed", e)
            fail("Something went wrong while opening this document.")
        }
    }

    /** A copy unlocked from this very file is reused ([UnlockedCopies]); otherwise ask for the password. */
    private suspend fun openUnlockedCopyOrAskPassword() {
        val paths = docPaths ?: return fail(MSG_MOVED)
        val copy = withContext(Dispatchers.IO) { UnlockedCopies.currentCopy(paths, documentFile) }
        if (copy != null) {
            try {
                finishOpening(openHandle(copy), sourceChanged = false)
                return
            } catch (e: PdfOpenException) {
                Log.w(TAG, "Unlocked copy unusable, asking for the password again", e)
            }
        }
        _loadState.value = LoadState.NeedsPassword()
    }

    /** Unlocks a password-protected PDF into a working copy and opens that. */
    fun submitPassword(password: String) {
        if (_loadState.value !is LoadState.NeedsPassword) return
        if (password.isEmpty()) {
            _loadState.value = LoadState.NeedsPassword("Enter the password.")
            return
        }
        _loadState.value = LoadState.Loading("Unlocking…")
        viewModelScope.launch {
            try {
                val paths = docPaths ?: return@launch fail(MSG_MOVED)
                val copy = paths.renditionFile(documentFile.name)
                // The version unlocked, captured first: if the file changes meanwhile the record won't match.
                val (size, lastModified) = withContext(Dispatchers.IO) {
                    paths.ensureDirs()
                    UnlockedCopies.clearRecord(paths, documentFile.name)
                    documentFile.length() to documentFile.lastModified()
                }
                if (!PdfUnlocker.unlock(documentFile, password, copy)) {
                    _loadState.value = LoadState.NeedsPassword("Wrong password. Try again.")
                    return@launch
                }
                withContext(Dispatchers.IO) { UnlockedCopies.record(paths, documentFile.name, size, lastModified) }
                finishOpening(openHandle(copy), sourceChanged = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                fail(e.message ?: "This PDF couldn't be unlocked.")
            } catch (e: PdfOpenException) {
                fail(openErrorMessage(e))
            } catch (e: Exception) {
                Log.w(TAG, "Unlocking ${documentFile.name} failed", e)
                fail("This PDF couldn't be unlocked.")
            }
        }
    }

    /**
     * Opens [pdf] without letting cancellation leak the handle: if the editor closed meanwhile the
     * handle is closed right away, otherwise it is adopted (and closed in [onCleared]).
     */
    private suspend fun openHandle(pdf: File): PdfDocumentHandle = withContext(NonCancellable) {
        val opened = PdfDocumentHandle.open(pdf, pageSizeCacheDir)
        if (cleared) {
            closeInBackground(opened)
            throw CancellationException("Editor closed while opening")
        }
        handle?.let(::closeInBackground)
        handle = opened
        opened
    }

    private fun beginSession(paths: LibraryPaths) {
        val handle = container.annotationStore.reserveSave(paths, documentFile.name)
        if (cleared) handle.complete(Unit) else session = handle
    }

    private suspend fun finishOpening(opened: PdfDocumentHandle, sourceChanged: Boolean) {
        val paths = docPaths ?: return fail(MSG_MOVED)
        // Also waits for another editor of this document to finish saving (closed and reopened).
        val load = container.annotationStore.loadResult(paths, documentFile.name, ownReservation = session)
        val saved = (load as? SidecarLoad.Loaded)?.file
        val unpreserved = when (load) {
            is SidecarLoad.Loaded -> load.unpreserved
            is SidecarLoad.Unreadable -> load.unpreserved
            SidecarLoad.Missing -> emptyList()
        }
        savesBlocked = unpreserved.isNotEmpty()
        val sizeNow = withContext(Dispatchers.IO) {
            settings.markOpened(documentFile.name)
            documentFile.length()
        }

        val initial = saved?.annotations ?: DocumentAnnotations.EMPTY
        undoStack = UndoStack(initial)
        savedVersion = undoStack.version
        lastSavedAt = saved?.savedAt?.takeIf { it > 0L }
        lastViewState = initial.viewState
        lastSavedViewState = saved?.annotations?.viewState
        _currentPage.value = initial.viewState.page.coerceIn(0, (opened.pageCount - 1).coerceAtLeast(0))

        // With a pen, start in the last used tool; otherwise in View so a finger scrolls first.
        val savedTools = settings.current.toolState
        _toolState.value = if (settings.current.stylusOnly == true) savedTools else savedTools.copy(tool = Tool.VIEW)

        publish()
        _loadState.value = LoadState.Ready(opened, initial.viewState)
        when {
            savesBlocked -> message(MSG_SAVES_BLOCKED, long = true)
            load is SidecarLoad.Unreadable -> message(MSG_UNREADABLE, long = true)
            load is SidecarLoad.Loaded && load.fromBackup -> message(MSG_FROM_BACKUP, long = true)
        }
        // A PDF's marks may belong to an earlier file of the same name, or the file was replaced.
        val pdfChanged = docType == DocType.PDF && saved != null && !initial.isEmpty &&
            !sidecarMatchesSource(saved.source, DocType.PDF, sizeNow)
        if (pdfChanged) message(MSG_SOURCE_CHANGED, long = true)
        if (sourceChanged) {
            if (!initial.isEmpty) message(MSG_SOURCE_CHANGED, long = true)
            // One-time: without this the rendition manager keeps reporting the change on every open.
            container.appScope.launch {
                runCatching { container.renditions.acknowledgeSourceChange(paths, documentFile.name) }
            }
        }
    }

    private fun fail(message: String) {
        _loadState.value = LoadState.Error(message)
    }

    private fun openErrorMessage(e: PdfOpenException): String = when (e.reason) {
        PdfOpenException.Reason.NOT_FOUND -> MSG_MISSING
        PdfOpenException.Reason.PASSWORD_PROTECTED -> "This document is password-protected."
        PdfOpenException.Reason.CORRUPT -> "This file is damaged or isn't a valid PDF."
        PdfOpenException.Reason.IO -> "The document couldn't be read. Check that it's still available and try again."
    }

    // ---------------------------------------------------------------- Canvas callbacks

    fun onStrokesAdded(pageIndex: Int, strokes: List<Stroke>) {
        if (strokes.isNotEmpty()) push(EditCommand.AddStrokes(pageIndex, strokes))
    }

    fun onStrokesErased(pageIndex: Int, strokeIds: Set<String>) {
        EditCommand.EraseStrokes.of(undoStack.current, pageIndex, strokeIds)?.let(::push)
    }

    fun onNoteTapped(pageIndex: Int, noteId: String) {
        val note = undoStack.current.page(pageIndex).notes.firstOrNull { it.id == noteId } ?: return
        openNoteEditor(NoteEditorRequest(pageIndex, note, isNew = false))
    }

    fun onNotePlacementRequested(pageIndex: Int, x: Float, y: Float) {
        val now = System.currentTimeMillis()
        val note = Note(newAnnotationId(), x, y, "", _toolState.value.noteColor, now, now)
        // A note placed while notes are hidden would vanish as soon as it is added.
        _notesVisible.value = true
        openNoteEditor(NoteEditorRequest(pageIndex, note, isNew = true))
    }

    private fun openNoteEditor(request: NoteEditorRequest?) {
        noteDraft = null
        _noteEditor.value = request
    }

    fun onNoteMoved(pageIndex: Int, noteId: String, x: Float, y: Float) {
        val old = undoStack.current.page(pageIndex).notes.firstOrNull { it.id == noteId } ?: return
        if (old.x == x && old.y == y) return
        push(EditCommand.MoveNote(pageIndex, old, old.copy(x = x, y = y)))
    }

    fun onCurrentPageChanged(pageIndex: Int) {
        _currentPage.value = pageIndex
    }

    /** First pen contact: turn on palm rejection (once, if undecided) and leave the View tool. */
    fun onStylusDetected() {
        if (settings.current.stylusOnly == null) {
            viewModelScope.launch(Dispatchers.IO) { settings.update { it.copy(stylusOnly = true) } }
            message("Pen detected — fingers now scroll. Change in Settings.")
        }
        if (!toolChosenByUser && _toolState.value.tool == Tool.VIEW) {
            _toolState.value = _toolState.value.copy(tool = Tool.HIGHLIGHTER)
        }
    }

    // ---------------------------------------------------------------- Notes

    /** Done in the note editor. A new note is added only with text; an emptied existing note is kept (the UI confirms deletion). */
    fun finishNoteEdit(text: String, color: Int) {
        val request = _noteEditor.value ?: return
        openNoteEditor(null)
        val clean = text.trim()
        if (clean.isEmpty()) return
        val page = request.pageIndex
        if (request.isNew) {
            val note = fitOnPage(page, request.note.copy(text = clean, color = color))
            push(EditCommand.AddNote(page, note))
            if (color != _toolState.value.noteColor) _toolState.value = _toolState.value.copy(noteColor = color)
        } else {
            val current = undoStack.current.page(page).notes.firstOrNull { it.id == request.note.id } ?: return
            if (current.text == clean && current.color == color) return
            val edited = current.copy(text = clean, color = color, modifiedAt = System.currentTimeMillis())
            push(EditCommand.EditNote(page, current, fitOnPage(page, edited)))
        }
    }

    /** Delete in the note editor (a new note is simply discarded). */
    fun deleteEditedNote() {
        val request = _noteEditor.value ?: return
        openNoteEditor(null)
        if (request.isNew) return
        val notes = undoStack.current.page(request.pageIndex).notes
        val index = notes.indexOfFirst { it.id == request.note.id }
        if (index >= 0) push(EditCommand.DeleteNote(request.pageIndex, notes[index], index))
    }

    fun dismissNoteEditor() {
        openNoteEditor(null)
    }

    /** The note editor reports every change, so a draft can be committed when the app is left. */
    fun updateNoteDraft(noteId: String, text: String, color: Int) {
        if (_noteEditor.value?.note?.id == noteId) noteDraft = NoteDraft(noteId, text, color)
    }

    /**
     * Turns the note being typed into a real edit (so background and final saves include it) and
     * keeps the editor open on the now-existing note. Nothing happens for a blank or unchanged draft.
     */
    private fun commitNoteDraft() {
        val request = _noteEditor.value ?: return
        val draft = noteDraft?.takeIf { it.noteId == request.note.id } ?: return
        val page = request.pageIndex
        val current = undoStack.current.page(page).notes.firstOrNull { it.id == request.note.id }
        if (!request.isNew && current == null) return
        val planned = plannedDraftNote(request, current, draft.text, draft.color, System.currentTimeMillis()) ?: return
        val note = fitOnPage(page, planned)
        if (request.isNew || current == null) {
            push(EditCommand.AddNote(page, note))
            if (draft.color != _toolState.value.noteColor) _toolState.value = _toolState.value.copy(noteColor = draft.color)
        } else {
            push(EditCommand.EditNote(page, current, note))
        }
        _noteEditor.value = request.copy(note = note, isNew = false)
    }

    /** Moves [note] so its whole card lies on the page (cards grow with their text). */
    private fun fitOnPage(pageIndex: Int, note: Note): Note {
        val size = handle?.pageSizes?.getOrNull(pageIndex) ?: return note
        val card = notePainter.noteBounds(note)
        val (x, y) = clampNotePosition(note.x, note.y, card.width(), card.height(), size.width, size.height)
        return if (x == note.x && y == note.y) note else note.copy(x = x, y = y)
    }

    // ---------------------------------------------------------------- Undo / redo

    fun undo() {
        if (undoStack.undo() != null) publish()
    }

    fun redo() {
        if (undoStack.redo() != null) publish()
    }

    private fun push(command: EditCommand) {
        undoStack.push(command)
        publish()
    }

    private fun publish() {
        _annotations.value = undoStack.current
        _canUndo.value = undoStack.canUndo
        _canRedo.value = undoStack.canRedo
        refreshSaveStatus()
    }

    private fun refreshSaveStatus() {
        val dirty = undoStack.version != savedVersion
        _dirty.value = dirty
        _saveStatus.value = when {
            saving -> SaveStatus.Saving
            dirty && lastSaveFailed -> SaveStatus.Failed
            dirty -> SaveStatus.Unsaved
            else -> SaveStatus.Saved(lastSavedAt)
        }
    }

    // ---------------------------------------------------------------- Tools & preferences

    fun selectTool(tool: Tool) {
        toolChosenByUser = true
        _toolState.value = _toolState.value.copy(tool = tool)
        // Placing notes while they are hidden would be confusing.
        if (tool == Tool.NOTE) _notesVisible.value = true
    }

    /** Colour/width/switch changes from the tool options popover. */
    fun updateToolState(transform: (ToolState) -> ToolState) {
        _toolState.value = transform(_toolState.value)
    }

    /** Show/Hide notes for this document while it is open (not remembered across documents). */
    fun setNotesVisible(visible: Boolean) {
        _notesVisible.value = visible
    }

    /** "Draw with finger" = palm rejection off. */
    fun setDrawWithFinger(enabled: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { settings.update { it.copy(stylusOnly = !enabled) } }
    }

    // ---------------------------------------------------------------- Saving

    /**
     * The screen registers a provider of the canvas's current position (null to unregister).
     * When unregistering it passes the canvas's last position so a final save still has it.
     */
    /**
     * Where a newly created canvas should start: the last position a canvas reported (a canvas
     * recreated with the activity carries on from there), else where the document opened.
     */
    fun resumeViewState(): ViewState = lastViewState

    fun setViewStateProvider(provider: (() -> ViewState?)?, lastKnown: ViewState? = null) {
        viewStateProvider = provider
        if (lastKnown != null) lastViewState = lastKnown
    }

    /**
     * The app is going to the background. The screen calls this on the Activity's ON_PAUSE, which
     * arrives without ProcessLifecycleOwner's delay. Commits a note being typed, then saves.
     */
    fun onBackground() {
        // After Discard the screen is still fading out; pressing Home then must not save after all.
        if (cleared || discarded) return
        commitNoteDraft()
        refreshViewState()
        viewModelScope.launch { save(SaveReason.BACKGROUND) }
    }

    /** The Save button. */
    fun saveNow() {
        viewModelScope.launch {
            if (save(SaveReason.MANUAL)) {
                _savedFlash.value = true
                savedFlashJob?.cancel()
                savedFlashJob = viewModelScope.launch {
                    delay(SAVED_FLASH_MS)
                    _savedFlash.value = false
                }
            }
        }
    }

    /** "Save" in the unsaved-changes dialog: save, then close if that worked. */
    fun saveAndClose() {
        viewModelScope.launch {
            if (save(SaveReason.CLOSE)) _events.send(EditorEvent.Close)
        }
    }

    /** "Discard": close without saving; the sidecar keeps the last saved state. */
    fun discardAndClose() {
        discarded = true
        _events.trySend(EditorEvent.Close)
    }

    fun onMessageAction(action: MessageAction) {
        when (action) {
            MessageAction.RETRY_SAVE -> viewModelScope.launch { save(SaveReason.MANUAL) }
        }
    }

    private suspend fun autosaveLoop() {
        while (true) {
            // Re-read every cycle so a changed setting applies without reopening the document.
            delay(settings.current.autosaveMinutes.coerceIn(1, 60) * 60_000L)
            save(SaveReason.AUTO)
        }
    }

    /**
     * Writes the sidecar. Saves run one at a time; a save requested during another one waits and
     * then snapshots the latest state. Returns true when everything up to the snapshot is saved.
     */
    private suspend fun save(reason: SaveReason): Boolean = saveMutex.withLock {
        if (_loadState.value !is LoadState.Ready || discarded) return false
        if (savesBlocked) {
            if (reason == SaveReason.MANUAL || reason == SaveReason.CLOSE) message(MSG_SAVES_BLOCKED, long = true)
            return false
        }
        if (undoStack.version == savedVersion && reason != SaveReason.MANUAL) return saveViewStateQuietly()
        val snapshot = takeSnapshot()
        saving = true
        refreshSaveStatus()
        try {
            writeSnapshot(snapshot)
            savedVersion = snapshot.version
            lastSavedAt = snapshot.savedAt
            lastSavedViewState = snapshot.annotations.viewState
            lastSaveFailed = false
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: DocumentMovedException) {
            Log.w(TAG, "Not saving ($reason): ${documentFile.name} moved", e)
            lastSaveFailed = true
            message(MSG_MOVED_SAVE, long = true)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Saving annotations ($reason) failed", e)
            lastSaveFailed = true
            message("Couldn't save your changes.", MessageAction.RETRY_SAVE, long = true)
            false
        } finally {
            saving = false
            refreshSaveStatus()
        }
    }

    /**
     * Nothing to save but the reading position: write it when it moved, without touching the save
     * status (the position alone doesn't make the document "dirty"). Caller holds [saveMutex].
     * Always reports success, as every edit is saved.
     */
    private suspend fun saveViewStateQuietly(): Boolean {
        refreshViewState()
        if (!viewStateMoved(lastSavedViewState, lastViewState)) return true
        val snapshot = takeSnapshot()
        try {
            writeSnapshot(snapshot)
            lastSavedViewState = snapshot.annotations.viewState
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Saving the reading position failed", e)
        }
        return true
    }

    private fun refreshViewState() {
        viewStateProvider?.let { provider ->
            runCatching(provider).getOrNull()?.let { lastViewState = it }
        }
    }

    private fun takeSnapshot(): SaveSnapshot {
        refreshViewState()
        return SaveSnapshot(
            annotations = undoStack.current.copy(viewState = lastViewState),
            version = undoStack.version,
            savedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Main-safe; only reads ViewModel state, so it can also run on the app scope after onCleared.
     * Runs under the library lock, and refuses ([DocumentMovedException]) once the document is no
     * longer in the library it was opened from, rather than writing over another document's sidecar.
     */
    private suspend fun writeSnapshot(snapshot: SaveSnapshot) {
        val type = docType ?: return
        val pinned = docPaths ?: throw DocumentMovedException()
        container.libraryStorage.withLibraryLock { current ->
            val source = withContext(Dispatchers.IO) {
                if (current != pinned || !isDocumentIn(documentFile, pinned.root)) throw DocumentMovedException()
                SourceInfo(documentFile.name, type, documentFile.length(), documentFile.lastModified())
            }
            container.annotationStore.save(pinned, AnnotationFile(source, snapshot.savedAt, snapshot.annotations))
        }
    }

    // ---------------------------------------------------------------- Export

    /** Exports with the live annotations (no save needed) and asks the screen to share the result. */
    fun shareAnnotatedPdf() {
        startExport {
            val result = export() ?: return@startExport
            _events.send(EditorEvent.Share(result.file, title))
        }
    }

    /**
     * Exports and copies the result to [destination] picked with ACTION_CREATE_DOCUMENT. The picker
     * has already created that document, so on failure or cancellation it is removed again instead
     * of being left behind empty; [ExportDestinations.discardUnwritten] keeps a destination that
     * already has content (an existing file the user chose to overwrite, or a finished copy), and
     * [ExportActions.copyToUri] itself removes a partly written one.
     */
    fun saveCopyTo(context: Context, destination: Uri) {
        val appContext = context.applicationContext
        val started = startExport {
            try {
                val result = export()
                if (result == null) {
                    ExportDestinations.discardUnwritten(appContext, destination)
                    return@startExport
                }
                ExportActions.copyToUri(appContext, result.file, destination)
                message("Saved a copy with your annotations.")
            } catch (e: CancellationException) {
                withContext(NonCancellable) { ExportDestinations.discardUnwritten(appContext, destination) }
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Copy to $destination failed", e)
                ExportDestinations.discardUnwritten(appContext, destination)
                message("Couldn't save the copy there. Try another location.", long = true)
            }
        }
        if (!started) viewModelScope.launch { ExportDestinations.discardUnwritten(appContext, destination) }
    }

    /** Cancel in the export progress dialog (or Back). The dialog closes at once. */
    fun cancelExport() {
        val job = exportJob ?: return
        exportJob = null
        _exporting.value = false
        job.cancel()
    }

    /** Runs one export at a time; returns false (and does nothing) while another one is running. */
    private fun startExport(block: suspend () -> Unit): Boolean {
        if (exportJob != null) return false
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                block()
            } finally {
                if (exportJob === coroutineContext[Job]) {
                    exportJob = null
                    _exporting.value = false
                }
            }
        }
        exportJob = job
        _exporting.value = true
        job.start()
        return true
    }

    /** Suggested file name for "Save a copy…". */
    val exportFileName: String get() = "$title (annotated).pdf"

    private suspend fun export(): ExportResult? {
        // Save first: a long export may outlast the process if the user switches apps meanwhile,
        // and the edits since the last save must not depend on it finishing. A failed save is
        // reported by save() itself; the export still goes ahead with the live annotations.
        if (undoStack.version != savedVersion) save(SaveReason.AUTO)
        return try {
            ExportActions.exportDocument(container, documentFile, undoStack.current).also {
                if (it.usedRasterFallback) message("This PDF was exported as page images, so its text isn't selectable.")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ExportException) {
            message(e.message ?: MSG_EXPORT_FAILED, long = true)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Export failed", e)
            message(MSG_EXPORT_FAILED, long = true)
            null
        }
    }

    // ---------------------------------------------------------------- Teardown

    override fun onCleared() {
        if (!discarded) commitNoteDraft()
        cleared = true
        ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver)
        val openHandle = handle
        handle = null
        val paths = docPaths
        refreshViewState()
        val needsSave = !discarded && !savesBlocked && _loadState.value is LoadState.Ready &&
            (undoStack.version != savedVersion || viewStateMoved(lastSavedViewState, lastViewState))
        val held = session
        session = null
        if (needsSave && paths != null) {
            val snapshot = takeSnapshot()
            // Held (or announced) before this returns, so reopening the document waits for this save.
            val reservation = held ?: container.annotationStore.reserveSave(paths, documentFile.name)
            // viewModelScope is already cancelled; the app scope outlives the screen. The handle is
            // closed only after the save, as the canvas may still be finishing a render with it.
            container.appScope.launch {
                try {
                    saveMutex.withLock { writeSnapshot(snapshot) }
                } catch (e: Exception) {
                    Log.w(TAG, "Final save of ${documentFile.name} failed", e)
                } finally {
                    reservation.complete(Unit)
                    openHandle?.let { runCatching { it.close() } }
                }
            }
        } else {
            held?.complete(Unit)
            if (openHandle != null) closeInBackground(openHandle)
        }
        _events.close()
    }

    private fun closeInBackground(h: PdfDocumentHandle) {
        container.appScope.launch(Dispatchers.IO) { runCatching { h.close() } }
    }

    private fun message(text: String, action: MessageAction? = null, long: Boolean = false) {
        _events.trySend(EditorEvent.Message(text, action, long))
    }

    /** Creates [EditorViewModel]s for `viewModel(factory = ...)`. */
    class Factory(
        private val container: AppContainer,
        private val documentFile: File,
        private val pageSizeCacheDir: File? = null,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(EditorViewModel::class.java)) { "Unknown ViewModel $modelClass" }
            return modelClass.cast(EditorViewModel(container, documentFile, pageSizeCacheDir))!!
        }
    }

    private companion object {
        const val TAG = "EditorViewModel"
        const val OPENING = "Opening…"
        const val MSG_MISSING = "This document is no longer in your library."
        const val MSG_MOVED = "This document has moved. Open it again from your library."
        const val MSG_MOVED_SAVE =
            "This document was moved or deleted, so your changes can't be saved. Open it again from your library."
        const val MSG_UNREADABLE =
            "Your saved highlights and notes for this document couldn't be read, so it opened without them. A copy was kept."
        const val MSG_FROM_BACKUP = "Your latest saved notes couldn't be read, so the previous saved version was opened."
        const val MSG_SAVES_BLOCKED =
            "Your saved highlights and notes for this document couldn't be read or backed up, so changes won't be saved over them."
        const val MSG_SOURCE_CHANGED = "This document changed since you annotated it; marks may be misaligned."
        const val MSG_EXPORT_FAILED = "Couldn't create the annotated PDF."
        const val SAVED_FLASH_MS = 1_500L
    }
}

/**
 * Top-left position for a note card of the given size so that it lies on the page, as close to
 * ([x],[y]) as possible. A card larger than the page is pinned to the top-left corner.
 */
internal fun clampNotePosition(
    x: Float,
    y: Float,
    cardWidth: Float,
    cardHeight: Float,
    pageWidth: Float,
    pageHeight: Float,
): Pair<Float, Float> {
    val maxX = (pageWidth - cardWidth).coerceAtLeast(0f)
    val maxY = (pageHeight - cardHeight).coerceAtLeast(0f)
    return x.coerceIn(0f, maxX) to y.coerceIn(0f, maxY)
}

/** True when [document] is a file directly inside the library folder [root]. Blocking IO. */
internal fun isDocumentIn(document: File, root: File): Boolean {
    if (!document.isFile) return false
    val parent = document.absoluteFile.parentFile ?: return false
    if (parent.normalize() == root.absoluteFile.normalize()) return true
    // The same folder reached through another path (e.g. /sdcard vs /storage/emulated/0).
    return try {
        parent.canonicalFile == root.canonicalFile
    } catch (e: IOException) {
        false
    }
}

/**
 * Whether a PDF's sidecar was written for the file now at its name. Compares type and size only:
 * a plain re-copy (over USB, by a sync app) changes the modification time but not the content.
 */
internal fun sidecarMatchesSource(saved: SourceInfo, type: DocType, size: Long): Boolean =
    saved.type == type && saved.size == size

/**
 * Whether the reading position moved enough since [saved] to be worth writing (null = no sidecar
 * yet, compared with the start of the document). Jitter below [VIEW_STATE_TOLERANCE] doesn't count.
 */
internal fun viewStateMoved(saved: ViewState?, current: ViewState): Boolean {
    val base = saved ?: ViewState()
    return base.page != current.page ||
        abs(base.pageOffsetFraction - current.pageOffsetFraction) > VIEW_STATE_TOLERANCE ||
        abs(base.zoom - current.zoom) > VIEW_STATE_TOLERANCE
}

private const val VIEW_STATE_TOLERANCE = 0.02f

/**
 * The note that an uncommitted note-editor draft becomes: the new note with its text, or the
 * edited existing note [current]. Null when there is nothing to commit (blank or unchanged text).
 */
internal fun plannedDraftNote(request: NoteEditorRequest, current: Note?, text: String, color: Int, now: Long): Note? {
    val clean = text.trim()
    if (clean.isEmpty()) return null
    if (request.isNew) return request.note.copy(text = clean, color = color)
    if (current == null || (current.text == clean && current.color == color)) return null
    return current.copy(text = clean, color = color, modifiedAt = now)
}
