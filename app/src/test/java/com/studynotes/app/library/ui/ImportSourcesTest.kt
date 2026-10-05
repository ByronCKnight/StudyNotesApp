package com.studynotes.app.library.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportSourcesTest {

    @Test
    fun deviceRowAlwaysDescribesLocalFiles() {
        assertEquals("Files on this tablet or phone", ImportSourceState(ImportSource.DEVICE).subtitle)
        assertEquals(
            "Files on this tablet or phone",
            ImportSourceState(ImportSource.DEVICE, installed = false, usedBefore = true).subtitle,
        )
    }

    @Test
    fun cloudRowsExplainThePickerUntilUsed() {
        assertEquals(
            "In the picker, tap ☰ and choose Drive",
            ImportSourceState(ImportSource.GOOGLE_DRIVE).subtitle,
        )
        assertEquals(
            "In the picker, tap ☰ and choose OneDrive",
            ImportSourceState(ImportSource.ONEDRIVE).subtitle,
        )
    }

    @Test
    fun cloudRowsShortenOnceUsed() {
        assertEquals("Pick from your Drive", ImportSourceState(ImportSource.GOOGLE_DRIVE, usedBefore = true).subtitle)
        assertEquals("Pick from your OneDrive", ImportSourceState(ImportSource.ONEDRIVE, usedBefore = true).subtitle)
    }

    @Test
    fun missingAppsOfferTheStore() {
        val subtitle = ImportSourceState(ImportSource.ONEDRIVE, installed = false, usedBefore = true).subtitle
        assertEquals("Not installed — tap to get it", subtitle)
    }

    @Test
    fun pickerOffersPdfWordAndGoogleDocs() {
        assertTrue("application/pdf" in IMPORT_MIME_TYPES)
        assertTrue("application/vnd.openxmlformats-officedocument.wordprocessingml.document" in IMPORT_MIME_TYPES)
        assertTrue("application/vnd.google-apps.document" in IMPORT_MIME_TYPES)
        assertEquals(3, IMPORT_MIME_TYPES.size)
    }

    @Test
    fun authoritiesMapToTheirSource() {
        assertEquals(ImportSource.DEVICE, ImportSource.forAuthority("com.android.externalstorage.documents"))
        assertEquals(ImportSource.DEVICE, ImportSource.forAuthority("com.android.providers.downloads.documents"))
        assertEquals(ImportSource.GOOGLE_DRIVE, ImportSource.forAuthority("com.google.android.apps.docs.storage"))
        assertEquals(ImportSource.GOOGLE_DRIVE, ImportSource.forAuthority("com.google.android.apps.docs.storage.legacy"))
        assertEquals(
            ImportSource.ONEDRIVE,
            ImportSource.forAuthority("com.microsoft.skydrive.content.StorageAccessProvider"),
        )
        assertEquals(ImportSource.ONEDRIVE, ImportSource.forAuthority("com.microsoft.skydrive.content.external"))
    }

    @Test
    fun unknownAuthoritiesMapToNothing() {
        assertNull(ImportSource.forAuthority(null))
        assertNull(ImportSource.forAuthority("com.dropbox.product.android.dbapp.document_provider.documents"))
        // A different app whose package merely starts with the same characters.
        assertNull(ImportSource.forAuthority("com.microsoft.skydrivefake.provider"))
        assertNull(ImportSource.forAuthority("com.android.providers.media.documents"))
    }

    @Test
    fun defaultsListEverySourceAsAvailable() {
        assertEquals(ImportSource.entries.toList(), DEFAULT_IMPORT_SOURCES.map { it.source })
        assertTrue(DEFAULT_IMPORT_SOURCES.all { it.installed && !it.usedBefore })
    }
}
