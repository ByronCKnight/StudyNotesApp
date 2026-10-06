package com.studynotes.app.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studynotes.app.BuildConfig

/**
 * Shows [content] only while no newer release is known; otherwise the blocking update screen.
 * Checks for a release whenever the app comes to the front.
 */
@Composable
fun UpdateGate(viewModel: UpdateViewModel, content: @Composable () -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.check() }
    when (val s = status) {
        UpdateStatus.UpToDate -> content()
        is UpdateStatus.Required -> UpdateRequiredScreen(s, viewModel)
    }
}

/**
 * Full-screen "update required": release notes, a download with progress, then the system
 * installer. Installing from the app needs the "install unknown apps" permission once; the first
 * Install tap opens that Settings switch and the install starts when the user comes back.
 */
@Composable
private fun UpdateRequiredScreen(state: UpdateStatus.Required, viewModel: UpdateViewModel) {
    val context = LocalContext.current
    val checking by viewModel.checking.collectAsStateWithLifecycle()
    var installAfterPermission by remember { mutableStateOf(false) }

    fun install() {
        val intent = viewModel.installIntent() ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            installAfterPermission = false
            context.startActivity(intent)
        } else {
            installAfterPermission = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // Back from the "install unknown apps" switch: continue with the install if it was allowed.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (installAfterPermission && context.packageManager.canRequestPackageInstalls()) install()
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.SystemUpdate,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(24.dp))
            Text("Update required", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text(
                text = "StudyNotes ${state.release.version} is out. You have ${BuildConfig.VERSION_NAME}; " +
                    "install the update to keep using the app. Your documents and notes stay as they are.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
            val notes = state.release.notes.trim()
            if (notes.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 480.dp),
                ) {
                    Text(
                        text = notes,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            val progress = state.progress
            when {
                progress != null -> {
                    if (progress < 0f) {
                        LinearProgressIndicator(Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth())
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = if (progress < 0f) "Downloading…" else "Downloading… ${(progress * 100).toInt()} %",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.apk != null -> {
                    Button(onClick = ::install, modifier = Modifier.widthIn(min = 220.dp)) {
                        Text("Install ${state.release.version}")
                    }
                    if (installAfterPermission) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Allow StudyNotes to install updates on the Settings screen, then come back here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.widthIn(max = 480.dp),
                        )
                    }
                }
                else -> {
                    Button(onClick = viewModel::download, modifier = Modifier.widthIn(min = 220.dp)) {
                        Text("Download update" + sizeLabel(state.release.apkSize))
                    }
                }
            }
            val error = state.error
            if (error != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 480.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { viewModel.check(force = true) }, enabled = !checking) {
                Text(if (checking) "Checking…" else "Check again")
            }
        }
    }
}

private fun sizeLabel(bytes: Long): String {
    if (bytes <= 0) return ""
    val mb = bytes / (1024.0 * 1024.0)
    return "  (${if (mb >= 10) "%.0f".format(mb) else "%.1f".format(mb)} MB)"
}
