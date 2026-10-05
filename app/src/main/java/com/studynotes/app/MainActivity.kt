package com.studynotes.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import com.studynotes.app.editor.EditorScreen
import com.studynotes.app.library.LibraryScreen
import com.studynotes.app.ui.theme.StudyNotesTheme
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleIncoming(intent)
        setContent {
            StudyNotesTheme {
                AppRoot(appViewModel, activity = this)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        // Relaunching a task from Recents replays its original "Open with" intent; don't re-import.
        if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        val uris = extractDocumentUris(intent)
        if (uris.isNotEmpty()) appViewModel.importIncoming(uris)
    }
}

/** One opening of a document. [id] differs for every open, so reopening always gets a fresh editor. */
data class OpenRequest(val path: String, val id: Long)

/**
 * Activity-scoped state: which document is open, imports handed over by other apps, and the
 * per-screen ViewModelStores (kept across configuration changes, cleared when a screen is left).
 */
class AppViewModel(app: Application, private val savedState: SavedStateHandle) : AndroidViewModel(app) {
    private val container = app.appContainer

    private val _openRequest = MutableStateFlow(restoreOpenRequest())
    val openRequest: StateFlow<OpenRequest?> = _openRequest.asStateFlow()

    private val _importing = MutableStateFlow(false)
    val importing: StateFlow<Boolean> = _importing.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    private val screenStores = HashMap<Long, ViewModelStore>()

    fun open(path: String) {
        val id = (savedState.get<Long>(KEY_NEXT_ID) ?: 1L)
        savedState[KEY_NEXT_ID] = id + 1
        setOpen(OpenRequest(path, id))
    }

    /** Closes editor [id]; ignored if another document has been opened since. */
    fun close(id: Long) {
        if (_openRequest.value?.id == id) setOpen(null)
    }

    /**
     * Copies documents received via "Open with" / "Share" into the library and opens the first.
     * Runs in viewModelScope so recomposition or a configuration change can't cancel the copy.
     */
    fun importIncoming(uris: List<Uri>) {
        viewModelScope.launch {
            _importing.value = true
            val result = try {
                container.libraryRepository.importUris(uris)
            } finally {
                _importing.value = false
            }
            result.imported.firstOrNull()?.let { open(it.path) }
            if (result.failures.isNotEmpty()) {
                _messages.trySend("Couldn't import " + result.failures.joinToString(", "))
            }
        }
    }

    fun storeFor(id: Long): ViewModelStore = screenStores.getOrPut(id) { ViewModelStore() }

    fun releaseStore(id: Long) {
        screenStores.remove(id)?.clear()
    }

    override fun onCleared() {
        screenStores.values.forEach { it.clear() }
        screenStores.clear()
    }

    private fun setOpen(request: OpenRequest?) {
        savedState[KEY_OPEN_PATH] = request?.path
        savedState[KEY_OPEN_ID] = request?.id
        _openRequest.value = request
    }

    private fun restoreOpenRequest(): OpenRequest? {
        val path = savedState.get<String>(KEY_OPEN_PATH) ?: return null
        val id = savedState.get<Long>(KEY_OPEN_ID) ?: return null
        return OpenRequest(path, id)
    }

    private companion object {
        const val KEY_OPEN_PATH = "open_path"
        const val KEY_OPEN_ID = "open_id"
        const val KEY_NEXT_ID = "next_id"
    }
}

/** URIs carried by ACTION_VIEW / ACTION_SEND / ACTION_SEND_MULTIPLE intents. */
private fun extractDocumentUris(intent: Intent): List<Uri> {
    val uris = LinkedHashSet<Uri>()
    when (intent.action) {
        Intent.ACTION_VIEW -> intent.data?.let(uris::add)
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(uris::add)
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let(uris::addAll)
        else -> return emptyList()
    }
    intent.clipData?.let { clip ->
        for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(uris::add)
    }
    return uris.toList()
}

@Composable
private fun AppRoot(appViewModel: AppViewModel, activity: ComponentActivity) {
    val openRequest by appViewModel.openRequest.collectAsStateWithLifecycle()
    val importing by appViewModel.importing.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(appViewModel) {
        appViewModel.messages.collect { snackbar.showSnackbar(it) }
    }

    // A Surface rather than a background, so everything below gets the theme's content colour
    // (otherwise untinted Text and icons default to black, unreadable in dark mode).
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = openRequest,
                contentKey = { it?.id },
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(160)) },
                label = "screen",
            ) { request ->
                if (request == null) {
                    LibraryScreen(onOpenDocument = { appViewModel.open(it.path) })
                } else {
                    ScreenScopedViewModels(appViewModel, request.id, activity) {
                        EditorScreen(documentFile = File(request.path), onClose = { appViewModel.close(request.id) })
                    }
                }
            }

            if (importing) {
                Surface(
                    modifier = Modifier.align(Alignment.Center),
                    shape = MaterialTheme.shapes.large,
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.5.dp)
                        Text("Importing…", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }

            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    // Stay clear of the editor's floating tool pill.
                    .padding(bottom = if (openRequest != null) EditorBottomChrome else 0.dp),
            )
        }
    }
}

/** Height of the editor's tool pill plus its margin, which root-level snackbars must clear. */
private val EditorBottomChrome = 88.dp

/**
 * Gives [content] its own ViewModelStore. The store lives in [appViewModel], so it survives
 * configuration changes, and is cleared when the screen really goes away (which runs the editor's
 * final save and closes its PDF).
 */
@Composable
private fun ScreenScopedViewModels(
    appViewModel: AppViewModel,
    id: Long,
    activity: ComponentActivity,
    content: @Composable () -> Unit,
) {
    val owner = remember(id) {
        object : ViewModelStoreOwner {
            override val viewModelStore = appViewModel.storeFor(id)
        }
    }
    DisposableEffect(owner) {
        onDispose {
            if (!activity.isChangingConfigurations) appViewModel.releaseStore(id)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
        content()
    }
}
