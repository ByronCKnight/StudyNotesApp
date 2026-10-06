package com.studynotes.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.studynotes.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Talks to GitHub: the latest release of the app's repository and its APK. The repository is
 * private, so both go through the GitHub API with a read-only token stamped into the build
 * ([BuildConfig.UPDATE_TOKEN], see the Release workflow). A build without a token (a local debug
 * build) does no update checks at all.
 */
class UpdateChecker(
    context: Context,
    private val repo: String = BuildConfig.UPDATE_REPO,
    private val token: String = BuildConfig.UPDATE_TOKEN,
) {
    private val context = context.applicationContext

    val enabled: Boolean get() = repo.isNotEmpty() && token.isNotEmpty()

    /** The latest release with an APK. Throws [IOException] when GitHub can't be reached or answers with an error. */
    suspend fun latestRelease(): Release = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/$repo/releases/latest", "application/vnd.github+json")
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("No release found (no release yet, or the built-in token has no access)")
                HttpURLConnection.HTTP_UNAUTHORIZED -> throw IOException("GitHub rejected the built-in access token (expired?)")
                else -> throw IOException("GitHub answered $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            UpdatePolicy.parseRelease(body) ?: throw IOException("The latest release has no APK attached")
        } finally {
            conn.disconnect()
        }
    }

    /** Where [release]'s APK is downloaded to; exists with the right size once [download] finished. */
    fun apkFile(release: Release): File = File(File(context.cacheDir, APK_DIR), release.apkName)

    fun isDownloaded(release: Release): Boolean {
        val f = apkFile(release)
        return f.isFile && (release.apkSize < 0 || f.length() == release.apkSize)
    }

    /**
     * Downloads [release]'s APK into the cache, reporting progress 0..1 (or -1 while the size is
     * unknown). Returns the finished file. Throws [IOException] on any failure; partial files are removed.
     */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val target = apkFile(release)
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        try {
            // The API asset URL answers with a redirect to a signed storage URL that must be fetched
            // WITHOUT the Authorization header, so redirects are followed by hand.
            var url = release.apiUrl.ifEmpty { release.publicUrl }
            var conn: HttpURLConnection? = null
            for (hop in 0..MAX_REDIRECTS) {
                val c = open(url, "application/octet-stream")
                val code = c.responseCode
                if (code in 300..399) {
                    val next = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    c.disconnect()
                    url = URI(url).resolve(next).toString()
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    c.disconnect()
                    throw IOException("Download failed: GitHub answered $code")
                }
                conn = c
                break
            }
            val c = conn ?: throw IOException("Too many redirects")
            try {
                val total = c.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                var done = 0L
                onProgress(if (total > 0) 0f else -1f)
                c.inputStream.use { input ->
                    part.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                if (release.apkSize >= 0 && done != release.apkSize) {
                    throw IOException("The download is incomplete ($done of ${release.apkSize} bytes)")
                }
            } finally {
                c.disconnect()
            }
            target.delete()
            if (!part.renameTo(target)) throw IOException("Couldn't save the download")
            onProgress(1f)
            target
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** Removes every downloaded APK except [keep]'s (an old update, or one that was installed). */
    fun pruneDownloads(keep: Release?) {
        val keepName = keep?.apkName
        File(context.cacheDir, APK_DIR).listFiles()?.forEach { if (it.name != keepName) it.delete() }
    }

    /** The system installer for a downloaded [apk]: Android installs it over this app (same signing key). */
    fun installIntent(apk: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private fun open(url: String, accept: String): HttpURLConnection {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("User-Agent", "StudyNotes/${BuildConfig.VERSION_NAME}")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        // The token is only for GitHub's API; the storage host a download redirects to rejects it.
        if (token.isNotEmpty() && conn.url.host == API_HOST) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }

    private companion object {
        const val API_HOST = "api.github.com"
        const val APK_DIR = "updates"
        const val APK_MIME = "application/vnd.android.package-archive"
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
