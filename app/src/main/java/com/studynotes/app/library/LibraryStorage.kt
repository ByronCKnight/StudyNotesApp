package com.studynotes.app.library

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.studynotes.app.data.AppSettings
import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.library.data.LibraryMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** What the UI must do to obtain access to the shared Documents/StudyNotes folder. */
sealed interface StorageAccessRequest {
    /** Android 11+: open this Settings screen ("Allow access to manage all files"). */
    data class OpenSettings(val intent: Intent) : StorageAccessRequest

    /** Android 8–10: request these runtime permissions. */
    data class RuntimePermissions(val permissions: Array<String>) : StorageAccessRequest

    /** Access already granted. */
    data object None : StorageAccessRequest
}

/**
 * Decides where the library lives.
 * - Preferred: /storage/emulated/0/Documents/StudyNotes (visible in Files apps and over USB).
 *   Android 11+: with All-files access the app sees everything in it; WITHOUT it the app can still
 *   create and read its own files there (scoped storage), it just can't see files added by a PC or
 *   another app — so use the public folder whenever a write probe succeeds.
 *   Android 8-10: needs READ/WRITE_EXTERNAL_STORAGE (requestLegacyExternalStorage on 10).
 * - Fallback when the public folder is not writable: app-specific storage
 *   (getExternalFilesDir(null)/StudyNotes, else filesDir/StudyNotes). A failed probe while
 *   [hasFolderAccess] is true, or after the public folder was already in use, is treated as a
 *   transient write error (full disk), not as missing access: the library stays public.
 * When access is granted later and the library is in the fallback, [refreshAccess] moves everything
 * (documents + .studynotes) into the public folder (renaming name clashes) and switches [paths].
 * [hasFolderAccess] = full visibility (isExternalStorageManager on 11+, permission granted on 8-10).
 *
 * CONTRACT (frozen).
 *
 * Construction only makes a cheap guess (no file writes); the real location is settled by a write
 * probe in the background right away, and before any [withLibraryLock] block runs.
 */
class LibraryStorage(context: Context, private val settings: AppSettings) {

    private val appContext: Context = context.applicationContext

    private val publicPaths = LibraryPaths(
        root = File(publicDocumentsDir(), LibraryPaths.PUBLIC_FOLDER_NAME),
        isPublic = true,
    )

    private val privatePaths = LibraryPaths(
        root = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, LibraryPaths.PUBLIC_FOLDER_NAME),
        isPublic = false,
    )

    private val _paths = MutableStateFlow(initialGuess())

    val paths: StateFlow<LibraryPaths> = _paths.asStateFlow()

    private val _documentsLeftBehind = MutableStateFlow(0)

    /**
     * Documents still in the private fallback folder although the library is in the shared folder:
     * moving them failed (e.g. not enough space for a copy between volumes) or couldn't be tried
     * yet. They are not listed; every [refreshAccess] tries again. 0 while the library is private.
     */
    internal val documentsLeftBehind: StateFlow<Int> = _documentsLeftBehind.asStateFlow()

    /** Serialises location decisions and migration with every operation that writes the library. */
    private val mutex = Mutex()

    /** Guarded by [mutex]: whether the probe has run at least once. */
    private var resolved = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        scope.launch { withLibraryLock { } }
    }

    fun hasFolderAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            LEGACY_PERMISSIONS.all {
                ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED
            }
        }

    fun accessRequest(): StorageAccessRequest = when {
        hasFolderAccess() -> StorageAccessRequest.None
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> StorageAccessRequest.OpenSettings(
            Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.fromParts("package", appContext.packageName, null),
            ),
        )
        else -> StorageAccessRequest.RuntimePermissions(LEGACY_PERMISSIONS.copyOf())
    }

    /** Re-checks permission (call on resume / after a permission result), migrating if newly granted. */
    suspend fun refreshAccess(): LibraryPaths = withContext(Dispatchers.IO) {
        mutex.withLock { resolveLocked() }
    }

    /**
     * Runs [block] with the library location settled and fixed: no probe, migration or switch of
     * [paths] happens until it returns. Everything that creates, renames or deletes library files
     * goes through here so it never races a migration. Not reentrant; keep blocks short (no network).
     */
    internal suspend fun <T> withLibraryLock(block: suspend (LibraryPaths) -> T): T = mutex.withLock {
        val current = if (resolved) _paths.value else withContext(Dispatchers.IO) { resolveLocked() }
        block(current)
    }

    /** Tells the media store about created/renamed/deleted files in the public folder, so they show up over USB (MTP). */
    internal fun notifyMediaStore(files: Collection<File>) {
        val publicFiles = files.filter { it.parentFile == publicPaths.root }
        if (publicFiles.isEmpty()) return
        MediaScannerConnection.scanFile(appContext, publicFiles.map { it.path }.toTypedArray(), null, null)
    }

    /** Must hold [mutex]. Blocking IO. */
    private fun resolveLocked(): LibraryPaths {
        val writable = publicFolderWritable()
        val target = when {
            writable -> publicPaths
            // The probe also fails on a full disk or while storage is briefly unavailable. With access
            // granted, or once the shared folder is in use, that is a write error for each operation
            // to report, not a reason to switch to the (empty) private folder.
            hasFolderAccess() || (resolved && _paths.value.isPublic) -> {
                Log.w(TAG, "The shared library folder is not writable right now; staying there")
                publicPaths
            }
            else -> privatePaths
        }
        if (target.isPublic) {
            // Only move documents into a folder that just proved writable.
            if (writable && LibraryMigration.documentsIn(privatePaths.root).isNotEmpty()) migrateLocked()
            _documentsLeftBehind.value = LibraryMigration.documentsIn(privatePaths.root).size
        } else {
            _documentsLeftBehind.value = 0
        }
        if (!target.ensureDirs()) Log.w(TAG, "Cannot create library folder ${target.root}")
        resolved = true
        _paths.value = target
        return target
    }

    private fun migrateLocked() {
        val result = LibraryMigration.migrate(privatePaths, publicPaths)
        for (moved in result.moved) {
            if (moved.oldName != moved.newFile.name) settings.renameRecent(moved.oldName, moved.newFile.name)
        }
        notifyMediaStore(result.moved.map { it.newFile })
        if (result.failed.isNotEmpty()) {
            // They stay complete in the private folder; the next refreshAccess retries.
            Log.w(TAG, "Could not move ${result.failed.size} document(s) to the shared folder")
        }
    }

    /** The write probe: can we create (and delete) a file inside Documents/StudyNotes/.studynotes? */
    private fun publicFolderWritable(): Boolean {
        if (Environment.getExternalStorageState() != Environment.MEDIA_MOUNTED) return false
        val meta = publicPaths.metaDir
        return try {
            if (!meta.isDirectory && !meta.mkdirs()) return false
            val probe = File(meta, ".probe-" + UUID.randomUUID())
            val created = probe.createNewFile()
            probe.delete()
            created
        } catch (e: IOException) {
            false
        } catch (e: SecurityException) {
            false
        }
    }

    /**
     * Cheap first guess for [paths] (permission check + one stat): the public folder if we have
     * access or used it before (its .studynotes folder exists), else the private fallback.
     */
    private fun initialGuess(): LibraryPaths =
        if (hasFolderAccess() || publicPaths.metaDir.isDirectory) publicPaths else privatePaths

    private companion object {
        const val TAG = "LibraryStorage"

        val LEGACY_PERMISSIONS = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        )

        // The deprecation steers apps towards MediaStore/SAF; a real folder on shared storage that
        // users can browse from a PC is exactly what this app needs, and all-files access (or the
        // legacy permission on Android 8-10) makes direct paths fully supported.
        @Suppress("DEPRECATION")
        fun publicDocumentsDir(): File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
    }
}
