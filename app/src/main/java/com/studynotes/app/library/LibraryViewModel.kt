package com.studynotes.app.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.studynotes.app.AppContainer
import com.studynotes.app.appContainer
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.data.Settings
import com.studynotes.app.data.SortOrder
import com.studynotes.app.export.ExportActions
import com.studynotes.app.export.ExportException
import com.studynotes.app.library.ui.DEFAULT_IMPORT_SOURCES
import com.studynotes.app.library.ui.DOWNLOADS_AUTHORITY
import com.studynotes.app.library.ui.DOWNLOAD_FOLDER_DOCUMENT_ID
import com.studynotes.app.library.ui.EXTERNAL_STORAGE_AUTHORITY
import com.studynotes.app.library.ui.ImportSource
import com.studynotes.app.library.ui.ImportSourceState
import com.studynotes.app.library.ui.documentsLeftBehindMessage
import com.studynotes.app.library.ui.importSummary
import com.studynotes.app.library.ui.sortAndFilter
import com.studynotes.app.model.DocType
import com.studynotes.app.model.LibraryDocument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Everything the library screen renders. Produced by [LibraryViewModel.uiState]. */
data class LibraryUiState(
    /** Documents to show: filtered by [query] and sorted by [sortOrder]. */
    val documents: List<LibraryDocument> = emptyList(),
    /** True when the library folder holds no documents at all (independent of the search). */
    val isLibraryEmpty: Boolean = true,
    /** False until the first folder scan finished, so the empty state never flashes on launch. */
    val loaded: Boolean = false,
    /** The search the [documents] were filtered with (may trail [LibraryViewModel.searchQuery] by a frame). */
    val query: String = "",
    val searchActive: Boolean = false,
    val sortOrder: SortOrder = SortOrder.RECENT,
    /** True when the library lives in the shared Documents/StudyNotes folder. */
    val isPublic: Boolean = false,
    /** Full visibility of the shared folder (files added by a computer or other apps show up). */
    val hasFolderAccess: Boolean = true,
    /** Human-readable library location, e.g. "Documents/StudyNotes". */
    val displayPath: String = "",
    val showOnboarding: Boolean = false,
    /** The slim "allow folder access" banner (hidden once dismissed for this session). */
    val showAccessBanner: Boolean = false,
    val autosaveMinutes: Int = Settings.DEFAULT_AUTOSAVE_MINUTES,
    /** "Draw with finger" switch: on unless the user (or pen detection) chose stylus-only input. */
    val drawWithFinger: Boolean = true,
    val importSources: List<ImportSourceState> = DEFAULT_IMPORT_SOURCES,
    /** Label of a running long operation ("Importing…"), or null when idle. */
    val busyMessage: String? = null,
    val dialog: LibraryDialog? = null,
)

/** A modal dialog owned by the view model, so it survives the screen leaving composition. */
sealed interface LibraryDialog {
    data class Rename(
        val document: LibraryDocument,
        /** Inline error under the name field (e.g. the repository's "name taken" message). */
        val error: String? = null,
        val inProgress: Boolean = false,
    ) : LibraryDialog

    data class ConfirmDelete(val document: LibraryDocument) : LibraryDialog
}

/** One-shot effects for the screen. */
sealed interface LibraryEvent {
    data class ShowMessage(
        val message: String,
        val actionLabel: String? = null,
        val action: MessageAction? = null,
        /** Keep it on screen longer (used when the message lists failures). */
        val long: Boolean = false,
    ) : LibraryEvent

    /** Start an activity (share chooser). The screen reports a missing handler itself. */
    data class LaunchIntent(val intent: Intent) : LibraryEvent
}

/** What a snackbar action button does. */
sealed interface MessageAction {
    data class OpenDocument(val file: File) : MessageAction
    data class Launch(val intent: Intent) : MessageAction
}

/**
 * State holder for the library screen: merges the folder contents, settings and search into
 * [uiState], and runs imports, renames, deletes and shares. Lives in the activity's
 * ViewModelStore, so search, dialogs and the banner dismissal survive opening a document.
 */
class LibraryViewModel(
    private val container: AppContainer,
    private val appContext: Context,
) : ViewModel() {

    private val storage = container.libraryStorage
    private val repository = container.libraryRepository
    private val settings = container.settings

    /** A running operation shown in the progress pill; identity-based so equal labels can overlap. */
    private class BusyTask(val label: String)

    /** Screen-session state that is not persisted in [AppSettings][com.studynotes.app.data.AppSettings]. */
    private data class Session(
        val searchActive: Boolean = false,
        // Assume access until checked, so the banner doesn't flash on launch.
        val hasFolderAccess: Boolean = true,
        val bannerDismissed: Boolean = false,
        val loaded: Boolean = false,
        val busy: List<BusyTask> = emptyList(),
        val dialog: LibraryDialog? = null,
        val importSources: List<ImportSourceState> = DEFAULT_IMPORT_SOURCES,
    )

    private val session = MutableStateFlow(Session())

    /**
     * Text of the search field. Compose state rather than part of [uiState] so the text field is
     * updated synchronously while typing (an async round trip can drop keystrokes).
     */
    var searchQuery: String by mutableStateOf("")
        private set

    private val _events = Channel<LibraryEvent>(Channel.BUFFERED)

    /** File names of just-imported documents the grid should scroll to; null when nothing pending. */
    private val _reveal = MutableStateFlow<Set<String>?>(null)
    val reveal: StateFlow<Set<String>?> = _reveal.asStateFlow()

    fun revealHandled() {
        _reveal.value = null
    }

    /** One-shot events; collect from a single place (the screen). */
    val events: Flow<LibraryEvent> = _events.receiveAsFlow()

    val uiState: StateFlow<LibraryUiState> =
        combine(repository.documents, settings.state, storage.paths, session, snapshotFlow { searchQuery }, ::buildState)
            .flowOn(Dispatchers.Default)
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                // Built synchronously so the first frame already knows whether to show onboarding.
                initialValue = buildState(
                    repository.documents.value,
                    settings.current,
                    storage.paths.value,
                    session.value,
                    searchQuery,
                ),
            )

    /** Where the grid was scrolled to, restored when returning from the editor. */
    var gridFirstVisibleItem: Int = 0
        private set
    var gridFirstVisibleOffset: Int = 0
        private set

    private var refreshJob: Job? = null
    private var refreshPending = false
    private var accessCheckPending = false

    /** Number of documents left in app storage that the user was last told about. */
    private var reportedLeftBehind = 0

    private fun buildState(
        documents: List<LibraryDocument>,
        settings: Settings,
        paths: LibraryPaths,
        session: Session,
        query: String,
    ) = LibraryUiState(
        documents = sortAndFilter(documents, settings.sortOrder, query),
        isLibraryEmpty = documents.isEmpty(),
        loaded = session.loaded || documents.isNotEmpty(),
        query = query,
        searchActive = session.searchActive,
        sortOrder = settings.sortOrder,
        isPublic = paths.isPublic,
        hasFolderAccess = session.hasFolderAccess,
        displayPath = paths.displayPath,
        showOnboarding = !settings.onboardingDone,
        showAccessBanner = session.loaded && !session.hasFolderAccess && !session.bannerDismissed,
        autosaveMinutes = settings.autosaveMinutes,
        drawWithFinger = settings.stylusOnly != true,
        importSources = session.importSources,
        busyMessage = session.busy.firstOrNull()?.label,
        dialog = session.dialog,
    )

    // ---- Refresh ----

    /** Rescans the library folder. */
    fun refresh() = requestRefresh(checkAccess = false)

    /**
     * Re-checks folder access (moving a private library into Documents/StudyNotes if access was just
     * granted), then rescans. Called on every resume and after a permission result.
     */
    fun refreshAccess() = requestRefresh(checkAccess = true)

    /** Coalesces refresh requests: while one runs, further requests schedule exactly one more pass. */
    private fun requestRefresh(checkAccess: Boolean) {
        refreshPending = true
        accessCheckPending = accessCheckPending || checkAccess
        if (refreshJob?.isActive == true) return
        refreshJob = viewModelScope.launch {
            while (refreshPending) {
                refreshPending = false
                val withAccess = accessCheckPending
                accessCheckPending = false
                runRefresh(withAccess)
            }
        }
    }

    private suspend fun runRefresh(checkAccess: Boolean) {
        if (checkAccess) {
            try {
                val paths = storage.refreshAccess()
                val access = withContext(Dispatchers.IO) { storage.hasFolderAccess() }
                session.update { it.copy(hasFolderAccess = access) }
                reportDocumentsLeftBehind(paths)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Folder access check failed", e)
            }
        }
        try {
            repository.refresh()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Library scan failed", e)
            message("Couldn't read the library folder.")
        } finally {
            session.update { it.copy(loaded = true) }
        }
        val sources = withContext(Dispatchers.IO) { loadImportSources() }
        session.update { it.copy(importSources = sources) }
    }

    /**
     * Tells the user when documents could not be moved into the shared folder (they are hidden
     * until a later attempt succeeds). Said again only when the number changes, not on every resume.
     */
    private fun reportDocumentsLeftBehind(paths: LibraryPaths) {
        val count = storage.documentsLeftBehind.value
        if (count == reportedLeftBehind) return
        reportedLeftBehind = count
        if (count > 0) message(documentsLeftBehindMessage(count, paths.displayPath), long = true)
    }

    private fun loadImportSources(): List<ImportSourceState> = ImportSource.entries.map { source ->
        ImportSourceState(
            source = source,
            installed = source.packageName?.let(::isAppInstalled) ?: true,
            usedBefore = source.authority?.let { settings.lastPickerUri(it) } != null,
        )
    }

    private fun isAppInstalled(packageName: String): Boolean = try {
        val pm = appContext.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, 0)
        }
        // A disabled app's DocumentsProvider doesn't show up in the picker either.
        info.applicationInfo?.enabled != false
    } catch (_: PackageManager.NameNotFoundException) {
        false
    } catch (e: RuntimeException) {
        // Package manager hiccup: assume installed, the row then shows the picker hint.
        Log.w(TAG, "Couldn't query $packageName", e)
        true
    }

    // ---- Folder access ----

    /** What the UI must launch to get access to Documents/StudyNotes. */
    fun accessRequest(): StorageAccessRequest = storage.accessRequest()

    /** Called when the user returns from the access screen or permission dialog. */
    fun onFolderAccessResult(permanentlyDenied: Boolean) {
        refreshAccess()
        if (permanentlyDenied) {
            val intent = Intent(
                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", appContext.packageName, null),
            )
            message(
                "Storage access is turned off. Allow it in app settings.",
                actionLabel = "Settings",
                action = MessageAction.Launch(intent),
                long = true,
            )
        }
    }

    /** Called when no settings screen for file access exists on this device. */
    fun onFolderAccessUnavailable() {
        message("Couldn't open access settings. Allow file access for StudyNotes in Settings › Apps.", long = true)
    }

    /** Hides the folder access banner until the app process restarts. */
    fun dismissAccessBanner() = session.update { it.copy(bannerDismissed = true) }

    /** Marks the first-run welcome as seen (both of its buttons do this). */
    fun finishOnboarding() = settings.update { it.copy(onboardingDone = true) }

    // ---- Search & sort ----

    /** Filters the grid by title; see [com.studynotes.app.library.ui.sortAndFilter]. */
    fun setQuery(text: String) {
        searchQuery = text
        // A document the search hides must not pull the grid to it once the search is cleared.
        _reveal.value = null
    }

    /** Opens or closes the inline search field; closing clears the query. */
    fun setSearchActive(active: Boolean) {
        if (!active) searchQuery = ""
        session.update { it.copy(searchActive = active) }
    }

    /** Persists the grid order. */
    fun setSort(order: SortOrder) = settings.update { it.copy(sortOrder = order) }

    /** Remembers the grid scroll position when the grid leaves composition (e.g. a document opens). */
    fun saveGridPosition(firstVisibleItem: Int, offset: Int) {
        gridFirstVisibleItem = firstVisibleItem
        gridFirstVisibleOffset = offset
    }

    // ---- Settings ----

    /** Editor autosave interval, in minutes (the editor re-reads it on every tick). */
    fun setAutosaveMinutes(minutes: Int) = settings.update { it.copy(autosaveMinutes = minutes.coerceIn(1, 60)) }

    /** Writes an explicit choice, so pen detection no longer changes it automatically. */
    fun setDrawWithFinger(enabled: Boolean) = settings.update { it.copy(stylusOnly = !enabled) }

    // ---- Thumbnails ----

    /** First-page thumbnail for a card, or null when the document can't be rendered. */
    suspend fun loadThumbnail(doc: LibraryDocument): ImageBitmap? = try {
        container.thumbnails.load(doc)?.asImageBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Thumbnail failed for ${doc.fileName}", e)
        null
    } catch (e: OutOfMemoryError) {
        // A placeholder card is better than taking the whole library screen down.
        Log.w(TAG, "Out of memory for the thumbnail of ${doc.fileName}", e)
        null
    }

    // ---- Import ----

    /**
     * Folder the system picker should open in for [source]: the folder of the last file imported
     * from it (DocumentsUI opens the parent of a document URI), the Download folder for a first
     * import from the device, or null to let the picker choose.
     */
    fun pickerInitialUri(source: ImportSource): Uri? {
        val last = when (source) {
            ImportSource.DEVICE ->
                settings.lastPickerUri(EXTERNAL_STORAGE_AUTHORITY) ?: settings.lastPickerUri(DOWNLOADS_AUTHORITY)
            else -> source.authority?.let { settings.lastPickerUri(it) }
        }
        return when {
            last != null -> last.toUri()
            source == ImportSource.DEVICE ->
                DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, DOWNLOAD_FOLDER_DOCUMENT_ID)
            else -> null
        }
    }

    /** Copies picked documents into the library and reports the outcome in a snackbar. */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        rememberPickerLocations(uris)
        val label = if (uris.size == 1) "Importing…" else "Importing ${uris.size} documents…"
        launchBusy(label) {
            val result = try {
                repository.importUris(uris)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Import failed", e)
                message("Couldn't import. Check your connection and try again.")
                return@launchBusy
            }
            if (result.imported.isNotEmpty()) _reveal.value = result.imported.mapTo(HashSet()) { it.name }
            val single = result.imported.singleOrNull()
            message(
                importSummary(
                    importedTitles = (result.imported - result.alreadyInLibrary.toSet()).map { it.nameWithoutExtension },
                    failures = result.failures,
                    alreadyTitles = result.alreadyInLibrary.map { it.nameWithoutExtension },
                ),
                actionLabel = single?.let { "Open" },
                action = single?.let { MessageAction.OpenDocument(it) },
                long = result.failures.isNotEmpty(),
            )
        }
    }

    /**
     * Remembers where each picked file came from: on-device folders so "This device" reopens there,
     * cloud providers (under their canonical authority, as the repository records them) so the
     * import sheet drops its "tap ☰" hint right away.
     */
    private fun rememberPickerLocations(uris: List<Uri>) {
        val used = mutableSetOf<ImportSource>()
        for (uri in uris) {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) continue
            val authority = uri.authority ?: continue
            val source = ImportSource.forAuthority(authority) ?: continue
            settings.setLastPickerUri(source.authority ?: authority, uri.toString())
            used += source
        }
        session.update { s ->
            s.copy(importSources = s.importSources.map { if (it.source in used) it.copy(usedBefore = true) else it })
        }
    }

    // ---- Rename ----

    /** Opens the rename dialog for [doc]. */
    fun requestRename(doc: LibraryDocument) = session.update { it.copy(dialog = LibraryDialog.Rename(doc)) }

    /** Clears the inline error once the user edits the name again. */
    fun clearRenameError() = session.update { s ->
        val dialog = s.dialog as? LibraryDialog.Rename
        if (dialog?.error == null) s else s.copy(dialog = dialog.copy(error = null))
    }

    /**
     * Renames the document of the open rename dialog. The dialog closes on success; on failure it
     * stays open with the repository's message (e.g. the name is taken) shown under the field.
     */
    fun confirmRename(newTitle: String) {
        val dialog = session.value.dialog as? LibraryDialog.Rename ?: return
        if (dialog.inProgress) return
        val title = newTitle.trim()
        when {
            title.isEmpty() -> updateRenameDialog { it.copy(error = "Enter a name") }
            title == dialog.document.title -> dismissDialog()
            else -> viewModelScope.launch {
                updateRenameDialog { it.copy(inProgress = true, error = null) }
                val error = try {
                    repository.rename(dialog.document, title)
                    container.thumbnails.invalidate(dialog.document.fileName)
                    null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IllegalArgumentException) {
                    e.message ?: "That name can't be used."
                } catch (e: Exception) {
                    Log.w(TAG, "Rename failed", e)
                    "Couldn't rename this document."
                }
                if (error == null) dismissDialog() else updateRenameDialog { it.copy(inProgress = false, error = error) }
            }
        }
    }

    private fun updateRenameDialog(transform: (LibraryDialog.Rename) -> LibraryDialog.Rename) =
        session.update { s ->
            val dialog = s.dialog as? LibraryDialog.Rename
            if (dialog == null) s else s.copy(dialog = transform(dialog))
        }

    // ---- Delete ----

    /** Asks for confirmation before deleting [doc]. */
    fun requestDelete(doc: LibraryDocument) = session.update { it.copy(dialog = LibraryDialog.ConfirmDelete(doc)) }

    /** Deletes the document of the open confirmation dialog, with its sidecar and rendition. */
    fun confirmDelete() {
        val dialog = session.value.dialog as? LibraryDialog.ConfirmDelete ?: return
        dismissDialog()
        val doc = dialog.document
        viewModelScope.launch {
            try {
                repository.delete(doc)
                container.thumbnails.invalidate(doc.fileName)
                message("Deleted “${doc.title}”")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Delete failed", e)
                message("Couldn't delete “${doc.title}”.")
            }
        }
    }

    /** Closes whichever dialog is open. */
    fun dismissDialog() = session.update { it.copy(dialog = null) }

    // ---- Share ----

    /** Shares the untouched original file (PDF or Word). */
    fun shareOriginal(doc: LibraryDocument) {
        viewModelScope.launch {
            try {
                // FileProvider resolves canonical paths (file system calls), so stay off the main thread.
                // shareIntent picks the MIME type from the extension (PDF or Word).
                val intent = withContext(Dispatchers.IO) { ExportActions.shareIntent(appContext, doc.file, doc.title) }
                _events.send(LibraryEvent.LaunchIntent(intent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Share failed", e)
                message("Couldn't share “${doc.title}”.")
            }
        }
    }

    /** Burns the saved highlights and notes into a PDF and opens the share sheet for it. */
    fun shareAnnotated(doc: LibraryDocument) {
        launchBusy("Preparing annotated PDF…") {
            try {
                val result = ExportActions.exportDocument(container, doc.file)
                val intent = withContext(Dispatchers.IO) {
                    ExportActions.shareIntent(appContext, result.file, result.file.nameWithoutExtension)
                }
                _events.send(LibraryEvent.LaunchIntent(intent))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ExportException) {
                message(e.message ?: EXPORT_FAILED)
            } catch (e: Exception) {
                Log.w(TAG, "Export failed", e)
                message(EXPORT_FAILED)
            }
        }
    }

    // ---- Helpers ----

    private fun launchBusy(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            val task = BusyTask(label)
            session.update { it.copy(busy = it.busy + task) }
            try {
                block()
            } finally {
                session.update { it.copy(busy = it.busy - task) }
            }
        }
    }

    private fun message(
        text: String,
        actionLabel: String? = null,
        action: MessageAction? = null,
        long: Boolean = false,
    ) {
        _events.trySend(LibraryEvent.ShowMessage(text, actionLabel, action, long))
    }

    /** Creates [LibraryViewModel] with the process-wide [AppContainer]. */
    class Factory(context: Context) : ViewModelProvider.Factory {
        private val appContext = context.applicationContext

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                "Unknown ViewModel class ${modelClass.name}"
            }
            @Suppress("UNCHECKED_CAST")
            return LibraryViewModel(appContext.appContainer, appContext) as T
        }
    }

    private companion object {
        const val TAG = "LibraryViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val EXPORT_FAILED = "Couldn't create the annotated PDF."
    }
}
