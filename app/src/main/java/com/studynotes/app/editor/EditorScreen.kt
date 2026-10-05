package com.studynotes.app.editor

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studynotes.app.appContainer
import com.studynotes.app.editor.canvas.CanvasListener
import com.studynotes.app.editor.canvas.DocumentCanvasView
import com.studynotes.app.editor.ui.EditorErrorScreen
import com.studynotes.app.editor.ui.EditorLoadingScreen
import com.studynotes.app.editor.ui.EditorPasswordScreen
import com.studynotes.app.editor.ui.EditorTopBar
import com.studynotes.app.editor.ui.ExportProgressDialog
import com.studynotes.app.editor.ui.GoToPageDialog
import com.studynotes.app.editor.ui.NoteEditor
import com.studynotes.app.editor.ui.NotesHiddenChip
import com.studynotes.app.editor.ui.PageChip
import com.studynotes.app.editor.ui.SaveChangesDialog
import com.studynotes.app.editor.ui.ToolPill
import com.studynotes.app.editor.ui.TopBarActions
import com.studynotes.app.editor.ui.TopBarState
import com.studynotes.app.editor.ui.saveStatusText
import com.studynotes.app.export.ExportActions
import com.studynotes.app.model.DocType
import com.studynotes.app.model.Stroke
import com.studynotes.app.pdf.PdfDocumentHandle
import com.studynotes.app.ui.theme.EditorColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * The document editor for one library file (PDF or DOCX).
 * CONTRACT (frozen signature). MainActivity provides a ViewModelStoreOwner scoped to this screen
 * instance (cleared when the screen leaves composition), so `viewModel(factory = ...)` inside gives
 * a ViewModel whose onCleared runs when the editor closes.
 * [onClose] navigates back to the library; call it only once any required save has completed.
 * Implementation: editor agent — see docs/ARCHITECTURE.md §Editor.
 */
@Composable
fun EditorScreen(
    documentFile: File,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val vm: EditorViewModel = viewModel(
        factory = remember(documentFile) {
            EditorViewModel.Factory(context.appContainer, documentFile, File(context.cacheDir, "pagesizes"))
        },
    )
    val currentOnClose by rememberUpdatedState(onClose)
    var closed by remember { mutableStateOf(false) }
    val close: () -> Unit = {
        if (!closed) {
            closed = true
            currentOnClose()
        }
    }

    val loadState by vm.loadState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val canvas = remember { CanvasHolder() }
    val rootFocus = remember { FocusRequester() }
    var showSaveDialog by remember { mutableStateOf(false) }

    // Events are handled only while the screen is visible: a share sheet started from a stopped
    // activity is silently blocked, and snackbars would time out unseen. The channel buffers
    // whatever arrives meanwhile.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.events.collect { event ->
                when (event) {
                    is EditorEvent.Message -> launch {
                        val result = snackbar.showSnackbar(
                            message = event.text,
                            actionLabel = event.action?.label,
                            duration = if (event.action != null || event.long) SnackbarDuration.Long else SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) event.action?.let(vm::onMessageAction)
                    }
                    is EditorEvent.Share -> try {
                        context.startActivity(ExportActions.shareIntent(context, event.file, event.title))
                    } catch (e: ActivityNotFoundException) {
                        launch { snackbar.showSnackbar("No app on this device can receive the PDF.") }
                    }
                    EditorEvent.Close -> close()
                }
            }
        }
    }

    // Save as soon as the activity pauses (leaving the app, Recents), without waiting for
    // ProcessLifecycleOwner's delayed ON_STOP, which a quick swipe-away can pre-empt.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE, lifecycleOwner) {
        canvas.view?.commitPendingInk()
        vm.onBackground()
    }

    // Back with unsaved edits asks first; otherwise (or while loading / on errors) leave at once.
    // A highlight still waiting for its snap-to-text result counts as an edit.
    val onBack: () -> Unit = {
        canvas.view?.commitPendingInk()
        if (vm.dirty.value) showSaveDialog = true else close()
    }
    BackHandler(enabled = !closed, onBack = onBack)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val backdrop = if (dark) EditorColors.CanvasDark else EditorColors.CanvasLight

    Box(
        Modifier
            .fillMaxSize()
            .background(backdrop)
            .onPreviewKeyEvent { event -> handleKey(event, canvas.view, vm) }
            .focusRequester(rootFocus)
            .focusTarget(),
    ) {
        when (val state = loadState) {
            is LoadState.Loading -> EditorLoadingScreen(vm.title, state.message, onBack = close)
            is LoadState.NeedsPassword -> EditorPasswordScreen(vm.title, state.error, onUnlock = vm::submitPassword, onBack = close)
            is LoadState.Error -> EditorErrorScreen(vm.title, state.message, onBack = close)
            is LoadState.Ready -> ReadyEditor(
                vm = vm,
                ready = state,
                canvas = canvas,
                backdropArgb = backdrop.toArgb(),
                snackbar = snackbar,
                onBack = onBack,
            )
        }

        if (loadState !is LoadState.Ready) {
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars),
            )
        }
    }

    // Hardware keyboard / pen-remote paging needs a focused node in this window.
    LaunchedEffect(loadState is LoadState.Ready) {
        if (loadState is LoadState.Ready) rootFocus.requestFocus()
    }

    if (showSaveDialog) {
        SaveChangesDialog(
            onSave = {
                showSaveDialog = false
                canvas.view?.commitPendingInk()
                vm.saveAndClose()
            },
            onDiscard = {
                showSaveDialog = false
                vm.discardAndClose()
            },
            onCancel = { showSaveDialog = false },
        )
    }
}

/** The canvas plus its overlaid chrome, once the document is open. */
@Composable
private fun ReadyEditor(
    vm: EditorViewModel,
    ready: LoadState.Ready,
    canvas: CanvasHolder,
    backdropArgb: Int,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val annotations by vm.annotations.collectAsStateWithLifecycle()
    val toolState by vm.toolState.collectAsStateWithLifecycle()
    val stylusOnly by vm.stylusOnly.collectAsStateWithLifecycle()
    val notesVisible by vm.notesVisible.collectAsStateWithLifecycle()
    val canUndo by vm.canUndo.collectAsStateWithLifecycle()
    val canRedo by vm.canRedo.collectAsStateWithLifecycle()
    val dirty by vm.dirty.collectAsStateWithLifecycle()
    val saveStatus by vm.saveStatus.collectAsStateWithLifecycle()
    val savedFlash by vm.savedFlash.collectAsStateWithLifecycle()
    val currentPage by vm.currentPage.collectAsStateWithLifecycle()
    val noteEditor by vm.noteEditor.collectAsStateWithLifecycle()
    val exporting by vm.exporting.collectAsStateWithLifecycle()

    var topBarPx by remember { mutableIntStateOf(0) }
    var pillPx by remember { mutableIntStateOf(0) }
    var showGoToPage by remember { mutableStateOf(false) }

    val pageCount = ready.handle.pageCount
    val bottomInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
    val bottomAreaPx = pillPx + bottomInsets.getBottom(density) + with(density) { (PillMargin + 8.dp).roundToPx() }
    val bottomAreaDp = with(density) { bottomAreaPx.toDp() }
    // Pages lay out between a side navigation bar / display cutout (landscape phones) while the
    // canvas itself, and so its backdrop and zoomed-in content, still reaches the screen edges.
    val layoutDirection = LocalLayoutDirection.current
    val sideInsets = WindowInsets.navigationBars.union(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    val sideInsetLeftPx = sideInsets.getLeft(density, layoutDirection)
    val sideInsetRightPx = sideInsets.getRight(density, layoutDirection)

    // "Saved · 2 min ago" ages, so re-evaluate it periodically.
    val now by produceState(System.currentTimeMillis(), saveStatus) {
        while (true) {
            value = System.currentTimeMillis()
            delay(STATUS_REFRESH_MS)
        }
    }
    val status = saveStatusText(saveStatus, now) ?: documentInfo(vm.docType, pageCount)

    val saveCopyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) vm.saveCopyTo(context, uri)
    }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                DocumentCanvasView(ctx).also { view ->
                    view.listener = CanvasCallbacks(vm, canvas)
                    canvas.view = view
                    vm.setViewStateProvider({ view.currentViewState() })
                }
            },
            modifier = Modifier.fillMaxSize(),
            onRelease = { view ->
                vm.setViewStateProvider(null, lastKnown = runCatching { view.currentViewState() }.getOrNull())
                // release() emits any highlight still waiting for its snap-to-text result through the
                // listener, so detach the listener only afterwards or that stroke would be lost.
                view.release()
                view.listener = null
                if (canvas.view === view) {
                    canvas.view = null
                    canvas.shownHandle = null
                }
            },
            update = { view ->
                if (canvas.shownHandle !== ready.handle) {
                    view.setDocument(ready.handle, vm.resumeViewState())
                    canvas.shownHandle = ready.handle
                }
                view.setAnnotations(annotations)
                view.setToolState(toolState)
                view.setStylusOnly(stylusOnly)
                view.setNotesVisible(notesVisible)
                view.setContentPadding(topBarPx, bottomAreaPx)
                view.setSideInsets(sideInsetLeftPx, sideInsetRightPx)
                view.setBackdropColor(backdropArgb)
            },
        )

        EditorTopBar(
            state = TopBarState(
                title = vm.title,
                status = status,
                statusIsError = saveStatus == SaveStatus.Failed,
                canUndo = canUndo,
                canRedo = canRedo,
                dirty = dirty,
                saving = saveStatus == SaveStatus.Saving,
                savedFlash = savedFlash,
                notesVisible = notesVisible,
                drawWithFinger = !stylusOnly,
                hasMarks = !annotations.isEmpty,
            ),
            actions = object : TopBarActions {
                override fun onBack() = onBack()
                // Pending ink (a highlight awaiting its snap) is committed first, so these act on
                // what the user sees.
                override fun onUndo() {
                    canvas.view?.commitPendingInk()
                    vm.undo()
                }
                override fun onRedo() {
                    canvas.view?.commitPendingInk()
                    vm.redo()
                }
                override fun onSave() {
                    canvas.view?.commitPendingInk()
                    vm.saveNow()
                }
                override fun onGoToPage() {
                    showGoToPage = true
                }
                override fun onToggleNotes() = vm.setNotesVisible(!notesVisible)
                override fun onShare() {
                    canvas.view?.commitPendingInk()
                    vm.shareAnnotatedPdf()
                }
                override fun onSaveCopy() {
                    canvas.view?.commitPendingInk()
                    try {
                        saveCopyLauncher.launch(vm.exportFileName)
                    } catch (e: ActivityNotFoundException) {
                        scope.launch { snackbar.showSnackbar("No app on this device can save files.") }
                    }
                }
                override fun onDrawWithFingerChange(enabled: Boolean) = vm.setDrawWithFinger(enabled)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .onSizeChanged { topBarPx = it.height },
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .windowInsetsPadding(bottomInsets)
                .padding(bottom = PillMargin),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            ) {
                PageChip(
                    pageIndex = currentPage,
                    pageCount = pageCount,
                    onClick = { showGoToPage = true },
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                // While notes are hidden, say so: otherwise they look lost.
                NotesHiddenChip(
                    visible = !notesVisible,
                    onShow = { vm.setNotesVisible(true) },
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
            ToolPill(
                toolState = toolState,
                supportsTextSelection = ready.handle.supportsTextSelection,
                onSelectTool = vm::selectTool,
                onUpdateTools = vm::updateToolState,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .onSizeChanged { pillPx = it.height },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = bottomAreaDp),
        )
    }

    noteEditor?.let { request ->
        key(request.note.id) {
            NoteEditor(
                request = request,
                onDone = vm::finishNoteEdit,
                onDelete = vm::deleteEditedNote,
                onCancel = vm::dismissNoteEditor,
                onDraftChange = { text, color -> vm.updateNoteDraft(request.note.id, text, color) },
            )
        }
    }

    if (showGoToPage) {
        GoToPageDialog(
            currentPage = currentPage,
            pageCount = pageCount,
            onGo = { index ->
                showGoToPage = false
                canvas.view?.scrollToPage(index)
            },
            onDismiss = { showGoToPage = false },
        )
    }

    if (exporting) ExportProgressDialog(onCancel = vm::cancelExport)
}

/** The live canvas and the document it was last given, so setDocument runs only on a new handle. */
private class CanvasHolder {
    var view: DocumentCanvasView? = null
    var shownHandle: PdfDocumentHandle? = null
}

/** Forwards canvas input to the ViewModel. */
private class CanvasCallbacks(private val vm: EditorViewModel, private val canvas: CanvasHolder) : CanvasListener {
    override fun onStrokesAdded(pageIndex: Int, strokes: List<Stroke>) = vm.onStrokesAdded(pageIndex, strokes)
    override fun onStrokesErased(pageIndex: Int, strokeIds: Set<String>) = vm.onStrokesErased(pageIndex, strokeIds)
    override fun onNoteTapped(pageIndex: Int, noteId: String) = vm.onNoteTapped(pageIndex, noteId)
    override fun onNotePlacementRequested(pageIndex: Int, x: Float, y: Float) = vm.onNotePlacementRequested(pageIndex, x, y)
    override fun onNoteMoved(pageIndex: Int, noteId: String, x: Float, y: Float) = vm.onNoteMoved(pageIndex, noteId, x, y)
    override fun onCurrentPageChanged(pageIndex: Int) = vm.onCurrentPageChanged(pageIndex)

    /**
     * The ViewModel may switch View -> Highlighter here. Hand the new tool to the canvas right away:
     * it decides how to treat this very gesture after this returns, before Compose recomposes.
     */
    override fun onStylusDetected() {
        vm.onStylusDetected()
        canvas.view?.setToolState(vm.toolState.value)
    }
}

/**
 * Keyboard and pen-remote shortcuts: Page Up/Down and arrow keys page through the document;
 * Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y undo and redo; Ctrl+S saves. Text fields live in dialogs and sheets
 * (their own windows), so these never steal keys from typing.
 */
private fun handleKey(event: KeyEvent, view: DocumentCanvasView?, vm: EditorViewModel): Boolean {
    if (view == null || event.type != KeyEventType.KeyDown) return false
    if (event.isCtrlPressed) {
        if (event.key == Key.Z || event.key == Key.Y || event.key == Key.S) view.commitPendingInk()
        when (event.key) {
            Key.Z -> if (event.isShiftPressed) vm.redo() else vm.undo()
            Key.Y -> vm.redo()
            Key.S -> vm.saveNow()
            else -> return false
        }
        return true
    }
    val screens = when (event.key) {
        Key.PageDown, Key.DirectionDown, Key.DirectionRight -> PAGE_SCREENS
        Key.PageUp, Key.DirectionUp, Key.DirectionLeft -> -PAGE_SCREENS
        else -> return false
    }
    view.scrollByScreens(screens)
    return true
}

/** Status line for a document that has never been saved, e.g. "PDF · 12 pages". */
private fun documentInfo(type: DocType?, pageCount: Int): String {
    val kind = if (type == DocType.DOCX) "Word" else "PDF"
    return "$kind · $pageCount ${if (pageCount == 1) "page" else "pages"}"
}

private val PillMargin = 12.dp
private const val PAGE_SCREENS = 0.9f
private const val STATUS_REFRESH_MS = 30_000L
