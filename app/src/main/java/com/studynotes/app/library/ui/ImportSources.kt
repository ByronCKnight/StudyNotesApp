package com.studynotes.app.library.ui

import com.studynotes.app.model.DocType

/**
 * MIME types offered in the system picker. Google Docs files are included because Drive can export
 * them (the repository asks for a PDF/DOCX stream). Never add CATEGORY_OPENABLE / EXTRA_LOCAL_ONLY
 * to the picker intent: they hide Google Docs and cloud roots.
 */
val IMPORT_MIME_TYPES: Array<String> =
    DocType.ALL_MIME_TYPES + "application/vnd.google-apps.document"

/** DocumentsProvider authority of the device's shared storage ("This device" in the picker). */
const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

/** DocumentsProvider authority of the Downloads root. */
const val DOWNLOADS_AUTHORITY = "com.android.providers.downloads.documents"

/** Document id of the shared Download folder, where most PDFs from the web and email end up. */
const val DOWNLOAD_FOLDER_DOCUMENT_ID = "primary:Download"

/**
 * The three ways to import. Cloud sources are reached through the same system picker: the Drive and
 * OneDrive apps register DocumentsProviders under [authority], so no API keys or sign-in are needed.
 */
enum class ImportSource(
    val title: String,
    /** App that provides the documents, or null for the device itself. */
    val packageName: String?,
    /** DocumentsProvider authority of the app, used to remember where the user last picked from. */
    val authority: String?,
    /** Name of the root as it appears in the picker's ☰ drawer. */
    val pickerRootName: String,
) {
    DEVICE("This device", null, null, "This device"),
    GOOGLE_DRIVE(
        "Google Drive",
        "com.google.android.apps.docs",
        "com.google.android.apps.docs.storage",
        "Drive",
    ),
    ONEDRIVE(
        "OneDrive",
        "com.microsoft.skydrive",
        "com.microsoft.skydrive.content.StorageAccessProvider",
        "OneDrive",
    );

    companion object {
        /** Authorities of on-device roots in the picker (shared storage and Downloads). */
        val DEVICE_AUTHORITIES: Set<String> = setOf(EXTERNAL_STORAGE_AUTHORITY, DOWNLOADS_AUTHORITY)

        /**
         * The source a picked document came from. Cloud apps expose several authorities (Drive also
         * has `….storage.legacy`), so any authority in the app's package namespace maps to the
         * app's canonical [authority] — the key under which the picker location is remembered.
         */
        fun forAuthority(authority: String?): ImportSource? = when {
            authority == null -> null
            authority in DEVICE_AUTHORITIES -> DEVICE
            else -> entries.firstOrNull { source ->
                val pkg = source.packageName
                pkg != null && (authority == source.authority || authority.startsWith("$pkg."))
            }
        }
    }
}

/** What the import sheet needs to know about one [ImportSource]. */
data class ImportSourceState(
    val source: ImportSource,
    /** False when the provider app is missing (or disabled); the row then links to the Play Store. */
    val installed: Boolean = true,
    /** True once a file from this provider has been imported, so the user knows the way in the picker. */
    val usedBefore: Boolean = false,
) {
    /** One-line hint under the row title. */
    val subtitle: String
        get() = when {
            source == ImportSource.DEVICE -> "Files on this tablet or phone"
            !installed -> "Not installed — tap to get it"
            usedBefore -> "Pick from your ${source.pickerRootName}"
            else -> "In the picker, tap ☰ and choose ${source.pickerRootName}"
        }
}

/** Default state before the installed/used checks have run: every source shown as available. */
val DEFAULT_IMPORT_SOURCES: List<ImportSourceState> = ImportSource.entries.map { ImportSourceState(it) }
