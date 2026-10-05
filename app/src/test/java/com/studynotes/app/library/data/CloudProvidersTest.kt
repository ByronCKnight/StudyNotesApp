package com.studynotes.app.library.data

import com.studynotes.app.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudProvidersTest {

    @Test
    fun googleDriveAuthoritiesAreCanonicalised() {
        assertEquals(
            CloudProviders.GOOGLE_DRIVE_AUTHORITY,
            CloudProviders.canonicalAuthority("com.google.android.apps.docs.storage"),
        )
        assertEquals(
            CloudProviders.GOOGLE_DRIVE_AUTHORITY,
            CloudProviders.canonicalAuthority("com.google.android.apps.docs.storage.legacy"),
        )
        assertNull(CloudProviders.canonicalAuthority("com.google.android.apps.docs.files"))
        assertNull(CloudProviders.canonicalAuthority("com.google.android.apps.docs.storagex"))
    }

    @Test
    fun oneDriveAuthoritiesAreCanonicalised() {
        for (authority in listOf(
            "com.microsoft.skydrive.content.StorageAccessProvider",
            "com.microsoft.skydrive.content.external",
            "com.microsoft.skydrive",
        )) {
            assertEquals(authority, CloudProviders.ONEDRIVE_AUTHORITY, CloudProviders.canonicalAuthority(authority))
        }
        assertNull(CloudProviders.canonicalAuthority("com.microsoft.skydrivex.provider"))
    }

    @Test
    fun otherAuthoritiesAreNotCloudProviders() {
        assertNull(CloudProviders.canonicalAuthority(null))
        assertNull(CloudProviders.canonicalAuthority("com.android.externalstorage.documents"))
        assertNull(CloudProviders.canonicalAuthority("com.dropbox.product.android.dbapp.document_provider.documents"))
    }

    @Test
    fun localSourcesAreRecognised() {
        assertTrue(CloudProviders.isLocal("file", null))
        assertTrue(CloudProviders.isLocal("content", "com.android.externalstorage.documents"))
        assertTrue(CloudProviders.isLocal("content", "com.android.providers.downloads.documents"))
        assertFalse(CloudProviders.isLocal("content", CloudProviders.GOOGLE_DRIVE_AUTHORITY))
        assertFalse(CloudProviders.isLocal("content", "com.google.android.gm.sapi"))
    }

    @Test
    fun virtualDocumentsExportAsPdfThenDocx() {
        val docx = DocType.DOCX.mimeType
        val pdf = DocType.PDF.mimeType
        assertEquals(DocType.PDF, CloudProviders.preferredExportType(arrayOf("text/plain", docx, pdf)))
        assertEquals(DocType.DOCX, CloudProviders.preferredExportType(arrayOf("application/rtf", docx)))
        assertNull(CloudProviders.preferredExportType(arrayOf("text/csv", "application/vnd.ms-excel")))
        assertNull(CloudProviders.preferredExportType(emptyArray()))
        assertNull(CloudProviders.preferredExportType(null))
    }
}
