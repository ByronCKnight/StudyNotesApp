package com.studynotes.app.update

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studynotes.app.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** What the update gate shows. */
sealed interface UpdateStatus {
    /** Nothing newer is known: the app is usable. */
    data object UpToDate : UpdateStatus

    /**
     * A newer release exists and must be installed first. [progress] is 0..1 while the APK is
     * downloading (-1 while its size is unknown), [apk] the finished download, [error] why the last
     * download attempt failed.
     */
    data class Required(
        val release: Release,
        val progress: Float? = null,
        val apk: File? = null,
        val error: String? = null,
    ) : UpdateStatus
}

/**
 * Keeps the app on the latest GitHub release. On every start (and whenever the app comes back to
 * the front, at most every [CHECK_INTERVAL_MS]) the latest release is fetched; a newer one blocks
 * the app until it is installed. A required update is remembered, so going offline does not get
 * around it, and the app opens normally only while no newer release is known.
 */
class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val checker = UpdateChecker(app)
    private val prefs = app.getSharedPreferences("studynotes_update", Context.MODE_PRIVATE)
    private val installed: AppVersion = AppVersion.parse(BuildConfig.VERSION_NAME) ?: AppVersion(0, 0, 0)

    private val _status = MutableStateFlow(restore())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Set while a check is running (the gate shows nothing different; "Try again" is disabled). */
    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private var lastCheck = Long.MIN_VALUE / 2
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    /** Asks GitHub for the latest release; [force] ignores the throttle. No-op in builds without a token. */
    fun check(force: Boolean = false) {
        if (!checker.enabled) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastCheck < CHECK_INTERVAL_MS) return
        if (checkJob?.isActive == true) return
        lastCheck = now
        checkJob = viewModelScope.launch {
            _checking.value = true
            try {
                val latest = checker.latestRelease()
                if (UpdatePolicy.updateRequired(installed, latest.version)) {
                    remember(latest)
                    _status.update { current ->
                        if (current is UpdateStatus.Required && current.release == latest) current else {
                            downloadJob?.cancel()
                            UpdateStatus.Required(latest, apk = checker.apkFile(latest).takeIf { checker.isDownloaded(latest) })
                        }
                    }
                } else {
                    forget()
                    checker.pruneDownloads(keep = null)
                    _status.value = UpdateStatus.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or GitHub down: keep what is known. A remembered required update stays required.
                Log.w(TAG, "Update check failed: ${e.message}")
                lastCheck = Long.MIN_VALUE / 2
            } finally {
                _checking.value = false
            }
        }
    }

    /** Downloads the required release's APK (no-op while one is downloading or already downloaded). */
    fun download() {
        val current = _status.value as? UpdateStatus.Required ?: return
        if (current.apk != null || downloadJob?.isActive == true) return
        val release = current.release
        downloadJob = viewModelScope.launch {
            setRequired { it.copy(progress = -1f, error = null) }
            try {
                val file = checker.download(release) { p -> setRequired(release) { it.copy(progress = p) } }
                setRequired(release) { it.copy(progress = null, apk = file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Update download failed", e)
                setRequired(release) { it.copy(progress = null, error = e.message ?: "Download failed") }
            }
        }
    }

    /** The installer for the downloaded APK, or null when nothing is downloaded. */
    fun installIntent(): Intent? {
        val apk = (_status.value as? UpdateStatus.Required)?.apk ?: return null
        if (!apk.isFile) {
            setRequired { it.copy(apk = null, error = "The download is gone, download it again") }
            return null
        }
        return checker.installIntent(apk)
    }

    private inline fun setRequired(release: Release? = null, transform: (UpdateStatus.Required) -> UpdateStatus.Required) {
        _status.update { s ->
            if (s is UpdateStatus.Required && (release == null || s.release == release)) transform(s) else s
        }
    }

    // ---- Remembered required update ----

    private fun restore(): UpdateStatus {
        if (!checker.enabled) return UpdateStatus.UpToDate
        val release = prefs.getString(KEY_RELEASE, null)?.let(Release::fromJson) ?: return UpdateStatus.UpToDate
        if (!UpdatePolicy.updateRequired(installed, release.version)) {
            // This is that release (or newer): the update happened.
            forget()
            checker.pruneDownloads(keep = null)
            return UpdateStatus.UpToDate
        }
        checker.pruneDownloads(keep = release)
        return UpdateStatus.Required(release, apk = checker.apkFile(release).takeIf { checker.isDownloaded(release) })
    }

    private fun remember(release: Release) = prefs.edit { putString(KEY_RELEASE, release.toJson()) }

    private fun forget() = prefs.edit { remove(KEY_RELEASE) }

    private companion object {
        const val TAG = "UpdateViewModel"
        const val KEY_RELEASE = "required_release"
        const val CHECK_INTERVAL_MS = 15 * 60 * 1000L
    }
}
