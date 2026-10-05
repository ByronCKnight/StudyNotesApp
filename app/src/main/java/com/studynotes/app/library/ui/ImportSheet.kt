package com.studynotes.app.library.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AddToDrive
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.TabletAndroid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch

/** Max width of bottom sheets on large screens (design language: ≤ 560 dp). */
val SheetMaxWidth = 560.dp

/** Input for [OpenDocumentsContract]: where the picker should start, if anywhere in particular. */
data class PickerRequest(val initialUri: Uri?)

/**
 * The system "open documents" picker ([ActivityResultContracts.OpenMultipleDocuments] with
 * [IMPORT_MIME_TYPES]) that can start in a given folder via EXTRA_INITIAL_URI. DocumentsUI opens the
 * parent folder of a document URI and silently ignores locations it can't resolve.
 */
class OpenDocumentsContract : ActivityResultContract<PickerRequest, List<Uri>>() {
    private val delegate = ActivityResultContracts.OpenMultipleDocuments()

    override fun createIntent(context: Context, input: PickerRequest): Intent =
        delegate.createIntent(context, IMPORT_MIME_TYPES).apply {
            input.initialUri?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> =
        delegate.parseResult(resultCode, intent)
}

/**
 * "Import" bottom sheet with three large rows: this device, Google Drive and OneDrive. Every row
 * opens the same system picker through [onPick] (cloud apps appear in it as sources); a missing
 * cloud app links to its Play Store page instead. The sheet animates away before the picker opens.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportSheet(
    sources: List<ImportSourceState>,
    showFolderTip: Boolean,
    folderPath: String,
    onPick: (ImportSource) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun closeThen(action: () -> Unit) {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            action()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetMaxWidth = SheetMaxWidth,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = "Import",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 4.dp),
            )
            Text(
                text = "PDF and Word files are copied into your library.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.size(16.dp))
            sources.forEach { state ->
                SourceRow(
                    state = state,
                    onClick = {
                        val packageName = state.source.packageName
                        if (state.installed || packageName == null) {
                            closeThen { onPick(state.source) }
                        } else {
                            closeThen { openStoreListing(context, packageName) }
                        }
                    },
                )
            }
            if (showFolderTip) {
                Text(
                    text = "Tip: files you copy into $folderPath also appear here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SourceRow(state: ImportSourceState, onClick: () -> Unit) {
    val muted = !state.installed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 76.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = if (muted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.secondaryContainer,
            contentColor = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(52.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(state.source.icon, contentDescription = null, modifier = Modifier.size(26.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(state.source.title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = state.subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = if (muted) Icons.AutoMirrored.Rounded.OpenInNew else Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private val ImportSource.icon: ImageVector
    get() = when (this) {
        ImportSource.DEVICE -> Icons.Rounded.TabletAndroid
        ImportSource.GOOGLE_DRIVE -> Icons.Rounded.AddToDrive
        ImportSource.ONEDRIVE -> Icons.Rounded.CloudQueue
    }

/** Opens the Play Store page of [packageName], or the web listing when no store app exists. */
private fun openStoreListing(context: Context, packageName: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$packageName".toUri()))
    } catch (_: ActivityNotFoundException) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()),
            )
        } catch (_: ActivityNotFoundException) {
            // No store and no browser: nothing sensible to open.
        }
    }
}
