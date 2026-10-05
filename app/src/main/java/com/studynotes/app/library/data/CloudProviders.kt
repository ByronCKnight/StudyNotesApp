package com.studynotes.app.library.data

import com.studynotes.app.model.DocType

/**
 * Knowledge about the document providers files are imported from: which ones are Google Drive /
 * OneDrive (tracked so the import sheet can stop showing "tap ☰ and choose Drive" hints) and which
 * ones are local (so a read failure isn't blamed on the network).
 */
object CloudProviders {
    /** Canonical Google Drive storage authority, as used with AppSettings.lastPickerUri. */
    const val GOOGLE_DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"

    /** Canonical OneDrive storage authority, as used with AppSettings.lastPickerUri. */
    const val ONEDRIVE_AUTHORITY = "com.microsoft.skydrive.content.StorageAccessProvider"

    const val GOOGLE_DRIVE_PACKAGE = "com.google.android.apps.docs"
    const val ONEDRIVE_PACKAGE = "com.microsoft.skydrive"

    /** Prefix of Google's virtual (Docs, Sheets, Slides ...) MIME types. */
    const val GOOGLE_APPS_MIME_PREFIX = "application/vnd.google-apps."

    private val LOCAL_AUTHORITIES = setOf(
        "com.android.externalstorage.documents",
        "com.android.providers.downloads.documents",
        "com.android.providers.media.documents",
        "media",
        "downloads",
        "com.google.android.apps.nbu.files.provider",
    )

    /**
     * Maps a content URI authority to the canonical authority recorded for it:
     * Drive's `.storage` and `.storage.legacy` -> [GOOGLE_DRIVE_AUTHORITY]; any `com.microsoft.skydrive.*`
     * -> [ONEDRIVE_AUTHORITY]; anything else -> null.
     */
    fun canonicalAuthority(authority: String?): String? = when {
        authority == null -> null
        authority == GOOGLE_DRIVE_AUTHORITY || authority == "$GOOGLE_DRIVE_AUTHORITY.legacy" -> GOOGLE_DRIVE_AUTHORITY
        authority == ONEDRIVE_PACKAGE || authority.startsWith("$ONEDRIVE_PACKAGE.") -> ONEDRIVE_AUTHORITY
        else -> null
    }

    /** True for file:// URIs and providers that serve files already on the device. */
    fun isLocal(scheme: String?, authority: String?): Boolean =
        scheme.equals("file", ignoreCase = true) || authority in LOCAL_AUTHORITIES

    /**
     * Format to export a virtual document (e.g. a Google Doc) as, from the stream types its
     * provider offers: PDF preferred, then DOCX; null if neither is offered.
     */
    fun preferredExportType(streamTypes: Array<String>?): DocType? {
        if (streamTypes == null) return null
        return listOf(DocType.PDF, DocType.DOCX).firstOrNull { type ->
            streamTypes.any { it.equals(type.mimeType, ignoreCase = true) }
        }
    }
}
