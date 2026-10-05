package com.studynotes.app.library

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.studynotes.app.library.ui.BusyPill
import com.studynotes.app.library.ui.CardInnerPadding
import com.studynotes.app.library.ui.DelayedProgress
import com.studynotes.app.library.ui.DeleteDialog
import com.studynotes.app.library.ui.DocumentCard
import com.studynotes.app.library.ui.DocumentCardActions
import com.studynotes.app.library.ui.EmptyLibrary
import com.studynotes.app.library.ui.FolderAccessBanner
import com.studynotes.app.library.ui.ImportSheet
import com.studynotes.app.library.ui.ImportSource
import com.studynotes.app.library.ui.LibraryTopBar
import com.studynotes.app.library.ui.NoMatches
import com.studynotes.app.library.ui.OnboardingScreen
import com.studynotes.app.library.ui.OpenDocumentsContract
import com.studynotes.app.library.ui.PickerRequest
import com.studynotes.app.library.ui.RenameDialog
import com.studynotes.app.library.ui.SettingsSheet
import com.studynotes.app.library.ui.rememberFolderAccessRequester
import kotlinx.coroutines.launch
import java.io.File

/** Minimum width of a grid column: 2 columns on phones, 5–7 on a 12.7" landscape tablet. */
private val MinCellWidth = 168.dp

/** Visual gap between thumbnails, horizontally and vertically. */
private val GridGap = 16.dp
private val GridRowGap = 20.dp

/** Room below the last row so the FAB (56 dp + 16 dp margin) never covers it. */
private val FabClearance = 88.dp

/** From this window width on, side margins grow from 16 to 24 dp. */
private val WideLayoutWidth = 600.dp

/** Horizontal inset of a top app bar title (the bar's own padding). */
private val AppBarTitleInset = 16.dp

/**
 * Home screen: the library grid, import, search/sort, settings, and first-run storage onboarding.
 * CONTRACT (frozen signature). Implementation: library agent — see docs/ARCHITECTURE.md §Library.
 */
@Composable
fun LibraryScreen(
    onOpenDocument: (File) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val viewModel: LibraryViewModel = viewModel(factory = remember(context) { LibraryViewModel.Factory(context) })
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val currentOnOpenDocument by rememberUpdatedState(onOpenDocument)

    // Covers the first start, returning from Settings / a file manager / the editor, and PC copies.
    LifecycleResumeEffect(viewModel) {
        viewModel.refreshAccess()
        onPauseOrDispose { }
    }

    val requestFolderAccess = rememberFolderAccessRequester(
        accessRequest = viewModel::accessRequest,
        onResult = viewModel::onFolderAccessResult,
        onUnavailable = viewModel::onFolderAccessUnavailable,
    )

    fun showMessage(message: String) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    fun startActivitySafely(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showMessage("No app on this device can do that.")
        }
    }

    // Launched from LibraryScreen (not the sheet), so the result arrives after the sheet is gone.
    val picker = rememberLauncherForActivityResult(OpenDocumentsContract()) { uris -> viewModel.import(uris) }
    val pickFrom: (ImportSource) -> Unit = { source ->
        try {
            picker.launch(PickerRequest(viewModel.pickerInitialUri(source)))
        } catch (_: ActivityNotFoundException) {
            showMessage("No file picker is available on this device.")
        }
    }

    // Events are handled only while the screen is visible: a share chooser started from a stopped
    // activity is silently blocked on Android 10+. Events sent meanwhile wait in the view model's
    // buffered channel and are handled on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is LibraryEvent.LaunchIntent -> startActivitySafely(event.intent)
                    is LibraryEvent.ShowMessage -> launch {
                        snackbarHostState.currentSnackbarData?.dismiss()
                        val result = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = event.actionLabel,
                            withDismissAction = event.long,
                            duration = if (event.long || event.actionLabel != null) SnackbarDuration.Long else SnackbarDuration.Short,
                        )
                        if (result == SnackbarResult.ActionPerformed) {
                            when (val action = event.action) {
                                is MessageAction.OpenDocument -> currentOnOpenDocument(action.file)
                                is MessageAction.Launch -> startActivitySafely(action.intent)
                                null -> Unit
                            }
                        }
                    }
                }
            }
        }
    }

    Crossfade(targetState = state.showOnboarding, modifier = modifier, label = "onboarding") { onboarding ->
        if (onboarding) {
            val settingsHint = remember(viewModel) { viewModel.accessRequest() is StorageAccessRequest.OpenSettings }
            OnboardingScreen(
                settingsHint = settingsHint,
                onAllowAccess = {
                    viewModel.finishOnboarding()
                    requestFolderAccess()
                },
                onNotNow = viewModel::finishOnboarding,
            )
        } else {
            LibraryContent(
                state = state,
                viewModel = viewModel,
                snackbarHostState = snackbarHostState,
                onOpenDocument = { currentOnOpenDocument(it) },
                onRequestFolderAccess = requestFolderAccess,
                onPick = pickFrom,
            )
        }
    }

    when (val dialog = state.dialog) {
        is LibraryDialog.Rename -> RenameDialog(
            document = dialog.document,
            error = dialog.error,
            inProgress = dialog.inProgress,
            onNameChange = viewModel::clearRenameError,
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissDialog,
        )
        is LibraryDialog.ConfirmDelete -> DeleteDialog(
            document = dialog.document,
            folderPath = state.displayPath,
            isPublic = state.isPublic,
            onConfirm = viewModel::confirmDelete,
            onDismiss = viewModel::dismissDialog,
        )
        null -> Unit
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryContent(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    snackbarHostState: SnackbarHostState,
    onOpenDocument: (File) -> Unit,
    onRequestFolderAccess: () -> Unit,
    onPick: (ImportSource) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState(viewModel.gridFirstVisibleItem, viewModel.gridFirstVisibleOffset)
    DisposableEffect(gridState) {
        onDispose { viewModel.saveGridPosition(gridState.firstVisibleItemIndex, gridState.firstVisibleItemScrollOffset) }
    }
    ScrollToTopOnReorder(gridState, state)
    RevealImported(gridState, state, viewModel)

    var showImport by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }

    // Extended at the top and while scrolling back up; icon-only while scrolling down the grid.
    val fabExpanded by remember {
        derivedStateOf { !gridState.canScrollBackward || !gridState.lastScrolledForward }
    }
    val showFab = state.loaded && !state.isLibraryEmpty && !state.searchActive

    // While searching the collapsing bar is not shown, so it must not swallow scroll deltas.
    val scrollModifier = if (state.searchActive) Modifier else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)

    // While searching the keyboard covers the bottom of the grid; keep the last row reachable. (The
    // IME is not part of the Scaffold insets, or the FAB would ride up behind the rename dialog.)
    val keyboardHeight = if (state.searchActive) WindowInsets.ime.asPaddingValues().calculateBottomPadding() else 0.dp

    // Phones get 16 dp side margins, tablets 24 dp; the title moves in to line up with the grid.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sideMargin = if (maxWidth >= WideLayoutWidth) 24.dp else 16.dp
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .then(scrollModifier),
            topBar = {
                LibraryTopBar(
                    searchActive = state.searchActive,
                    query = viewModel.searchQuery,
                    sortOrder = state.sortOrder,
                    titleStartPadding = sideMargin - AppBarTitleInset,
                    scrollBehavior = scrollBehavior,
                    onSearchActiveChange = viewModel::setSearchActive,
                    onQueryChange = viewModel::setQuery,
                    onSortChange = viewModel::setSort,
                    onOpenSettings = { showSettings = true },
                )
            },
            floatingActionButton = {
                AnimatedVisibility(visible = showFab, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                    ExtendedFloatingActionButton(
                        text = { Text("Import") },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        onClick = { showImport = true },
                        expanded = fabExpanded,
                        // One label whether expanded or not: the expanded label's text alone isn't
                        // exposed to accessibility services while it animates in.
                        modifier = Modifier.semantics { contentDescription = "Import" },
                    )
                }
            },
            snackbarHost = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    BusyIndicator(state.busyMessage)
                    SnackbarHost(snackbarHostState)
                }
            },
            contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        ) { padding ->
            LibraryBody(
                state = state,
                viewModel = viewModel,
                gridState = gridState,
                padding = padding,
                sideMargin = sideMargin,
                keyboardHeight = keyboardHeight,
                onOpenDocument = onOpenDocument,
                onRequestFolderAccess = onRequestFolderAccess,
                onImport = { showImport = true },
            )
        }
    }

    if (showImport) {
        ImportSheet(
            sources = state.importSources,
            showFolderTip = state.isPublic && state.hasFolderAccess,
            folderPath = state.displayPath,
            onPick = onPick,
            onDismiss = { showImport = false },
        )
    }
    if (showSettings) {
        SettingsSheet(
            displayPath = state.displayPath,
            isPublic = state.isPublic,
            hasFolderAccess = state.hasFolderAccess,
            drawWithFinger = state.drawWithFinger,
            autosaveMinutes = state.autosaveMinutes,
            onAllowFolderAccess = onRequestFolderAccess,
            onDrawWithFingerChange = viewModel::setDrawWithFinger,
            onAutosaveMinutesChange = viewModel::setAutosaveMinutes,
            onDismiss = { showSettings = false },
        )
    }
}


/** Everything under the top bar: the access banner and the grid (or its empty / no-match state). */
@Composable
private fun LibraryBody(
    state: LibraryUiState,
    viewModel: LibraryViewModel,
    gridState: LazyGridState,
    padding: PaddingValues,
    sideMargin: Dp,
    keyboardHeight: Dp,
    onOpenDocument: (File) -> Unit,
    onRequestFolderAccess: () -> Unit,
    onImport: () -> Unit,
) {
    val layoutDirection = LocalLayoutDirection.current
    val start = padding.calculateStartPadding(layoutDirection) + sideMargin
    val end = padding.calculateEndPadding(layoutDirection) + sideMargin
    val bottom = maxOf(padding.calculateBottomPadding(), keyboardHeight)
    Column(
        Modifier
            .fillMaxSize()
            .padding(top = padding.calculateTopPadding()),
    ) {
        AnimatedVisibility(
            visible = state.showAccessBanner && !state.searchActive,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            FolderAccessBanner(
                isPublic = state.isPublic,
                onAllow = onRequestFolderAccess,
                onDismiss = viewModel::dismissAccessBanner,
                modifier = Modifier.padding(start = start, end = end, top = 8.dp, bottom = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            val messagePadding = PaddingValues(start = start, end = end, bottom = bottom)
            when {
                !state.loaded -> DelayedProgress()
                state.isLibraryEmpty -> EmptyLibrary(
                    canCopyIntoFolder = state.isPublic && state.hasFolderAccess,
                    folderPath = state.displayPath,
                    onImport = onImport,
                    contentPadding = messagePadding,
                )
                state.documents.isEmpty() -> NoMatches(
                    query = state.query,
                    onClearSearch = { viewModel.setQuery("") },
                    contentPadding = messagePadding,
                )
                else -> DocumentGrid(
                    state = state,
                    gridState = gridState,
                    viewModel = viewModel,
                    onOpenDocument = onOpenDocument,
                    // The card pads itself (for its ripple); subtract that so thumbnails line up
                    // with the side margins and the gaps stay at GridGap.
                    contentPadding = PaddingValues(
                        start = start - CardInnerPadding,
                        end = end - CardInnerPadding,
                        top = 8.dp,
                        bottom = bottom + FabClearance,
                    ),
                )
            }
        }
    }
}

@Composable
private fun DocumentGrid(
    state: LibraryUiState,
    gridState: LazyGridState,
    viewModel: LibraryViewModel,
    onOpenDocument: (File) -> Unit,
    contentPadding: PaddingValues,
) {
    val currentOnOpen by rememberUpdatedState(onOpenDocument)
    val actions = remember(viewModel) {
        DocumentCardActions(
            onOpen = { currentOnOpen(it.file) },
            onRename = viewModel::requestRename,
            onShareOriginal = viewModel::shareOriginal,
            onShareAnnotated = viewModel::shareAnnotated,
            onDelete = viewModel::requestDelete,
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(MinCellWidth),
        state = gridState,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(GridGap - CardInnerPadding * 2),
        verticalArrangement = Arrangement.spacedBy(GridRowGap - CardInnerPadding * 2),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.documents, key = { it.fileName }, contentType = { "document" }) { document ->
            DocumentCard(
                document = document,
                loadThumbnail = viewModel::loadThumbnail,
                actions = actions,
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** Jumps back to the first document when the sort order or the search changes (not on first show). */
@Composable
private fun ScrollToTopOnReorder(gridState: LazyGridState, state: LibraryUiState) {
    val orderKey = state.sortOrder to state.query
    var appliedKey by remember { mutableStateOf(orderKey) }
    LaunchedEffect(orderKey) {
        if (orderKey != appliedKey) {
            appliedKey = orderKey
            gridState.requestScrollToItem(0)
        }
    }
}

/** After an import, brings the first imported document into view (it may sort off-screen). */
@Composable
private fun RevealImported(gridState: LazyGridState, state: LibraryUiState, viewModel: LibraryViewModel) {
    val reveal by viewModel.reveal.collectAsStateWithLifecycle()
    val documents by rememberUpdatedState(state.documents)
    LaunchedEffect(reveal) {
        val names = reveal ?: return@LaunchedEffect
        // Waits for the listing to catch up with the import (it refreshes right after).
        val index = withTimeoutOrNull(REVEAL_WAIT_MILLIS) {
            snapshotFlow { documents.indexOfFirst { it.fileName in names } }.first { it >= 0 }
        }
        if (index != null && gridState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
            gridState.animateScrollToItem(index)
        }
        // Only now: clearing the request restarts this effect, which would cancel the scroll.
        viewModel.revealHandled()
    }
}

private const val REVEAL_WAIT_MILLIS = 3_000L

/** The progress pill; AnimatedContent keeps the last message visible while it animates out. */
@Composable
private fun BusyIndicator(message: String?) {
    AnimatedContent(
        targetState = message,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "busy",
    ) { text ->
        if (text != null) BusyPill(text, Modifier.padding(bottom = 8.dp))
    }
}
