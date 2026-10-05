package com.studynotes.app.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cleans up after a failed "Save a copy…". ACTION_CREATE_DOCUMENT makes the provider create the
 * destination before the app writes anything, so a failed export or copy would otherwise leave an
 * empty or truncated PDF in Drive, OneDrive or Files (and synced on to the user's PC).
 *
 * Everything here is best-effort: some providers cannot delete (no FLAG_SUPPORTS_DELETE) or the
 * grant is already gone; those failures are logged and swallowed.
 */
internal object ExportDestinations {

    private const val TAG = "ExportDestinations"

    /**
     * For a "Save a copy…" whose export failed or was cancelled before anything was written to
     * [destination]: deletes it if it is still empty (size 0 or not reported), which is what the
     * picker just created. A destination with content is an existing file the user chose to
     * overwrite, and nothing has touched it yet, so it is kept. Main-safe; call it in
     * `withContext(NonCancellable)` when cleaning up after a cancellation.
     */
    suspend fun discardUnwritten(context: Context, destination: Uri): Unit = withContext(Dispatchers.IO) {
        val resolver = context.applicationContext.contentResolver
        val size = sizeOf(resolver, destination)
        if (size == null || size == 0L) deleteQuietly(resolver, destination)
    }

    /**
     * Deletes [destination] after writing to it failed part-way: whatever it held before was
     * truncated or partly overwritten, and what is there now is not a usable PDF. Blocking.
     */
    fun discardPartial(resolver: ContentResolver, destination: Uri): Unit = deleteQuietly(resolver, destination)

    private fun sizeOf(resolver: ContentResolver, uri: Uri): Long? = try {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
    } catch (e: Exception) {
        // Revoked grant or a provider that can't be queried: treat the size as unknown.
        Log.w(TAG, "Couldn't query $uri", e)
        null
    }

    private fun deleteQuietly(resolver: ContentResolver, uri: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't delete the failed copy at $uri", e)
        }
    }
}
