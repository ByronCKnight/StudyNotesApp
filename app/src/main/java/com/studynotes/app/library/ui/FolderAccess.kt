package com.studynotes.app.library.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.core.app.ActivityCompat
import com.studynotes.app.library.StorageAccessRequest

/**
 * Returns a function that asks for access to the shared library folder, the same way from every
 * entry point (onboarding, banner, settings):
 * - Android 11+: opens the "All files access" screen for this app, falling back to the generic list
 *   of apps when a device lacks the per-app screen; [onUnavailable] if neither exists.
 * - Android 8–10: requests the storage runtime permissions.
 *
 * [onResult] runs when the user comes back; `permanentlyDenied` is true when Android no longer shows
 * the permission dialog (the user must enable it in app settings).
 */
@Composable
fun rememberFolderAccessRequester(
    accessRequest: () -> StorageAccessRequest,
    onResult: (permanentlyDenied: Boolean) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val activity = LocalActivity.current
    val currentAccessRequest by rememberUpdatedState(accessRequest)
    val currentOnResult by rememberUpdatedState(onResult)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)

    val settingsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { currentOnResult(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        val denied = grants.filterValues { granted -> !granted }.keys
        // After a denial, "no rationale" means the system will not ask again.
        val permanentlyDenied = activity != null && denied.isNotEmpty() &&
            denied.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        currentOnResult(permanentlyDenied)
    }

    return remember(settingsLauncher, permissionLauncher) {
        {
            when (val request = currentAccessRequest()) {
                is StorageAccessRequest.OpenSettings -> try {
                    settingsLauncher.launch(request.intent)
                } catch (_: ActivityNotFoundException) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            settingsLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        } catch (_: ActivityNotFoundException) {
                            currentOnUnavailable()
                        }
                    } else {
                        currentOnUnavailable()
                    }
                }
                is StorageAccessRequest.RuntimePermissions -> permissionLauncher.launch(request.permissions)
                StorageAccessRequest.None -> currentOnResult(false)
            }
        }
    }
}
