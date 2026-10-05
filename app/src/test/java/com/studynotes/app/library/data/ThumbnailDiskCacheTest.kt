package com.studynotes.app.library.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ThumbnailDiskCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun key(fileName: String = "Lecture.pdf", sidecar: Long = 0L) =
        ThumbnailDiskCache.cacheKey(fileName, size = 1234L, lastModified = 99L, sidecarLastModified = sidecar)

    @Test
    fun everyInputChangesTheKey() {
        val base = ThumbnailDiskCache.cacheKey("a.pdf", 1L, 2L, 3L, 4L)
        assertNotEquals(base, ThumbnailDiskCache.cacheKey("b.pdf", 1L, 2L, 3L, 4L))
        assertNotEquals(base, ThumbnailDiskCache.cacheKey("a.pdf", 9L, 2L, 3L, 4L))
        assertNotEquals(base, ThumbnailDiskCache.cacheKey("a.pdf", 1L, 9L, 3L, 4L))
        assertNotEquals(base, ThumbnailDiskCache.cacheKey("a.pdf", 1L, 2L, 9L, 4L))
        assertNotEquals(base, ThumbnailDiskCache.cacheKey("a.pdf", 1L, 2L, 3L, 9L))
    }

    @Test
    fun diskNamesAreStableSafeAndGroupedPerDocument() {
        val name = ThumbnailDiskCache.fileName("Lecture: \"1\"/2.pdf", key())
        assertEquals(name, ThumbnailDiskCache.fileName("Lecture: \"1\"/2.pdf", key()))
        assertTrue(name, Regex("[0-9a-f]{16}-[0-9a-f]{24}\\.png").matches(name))

        val v1 = ThumbnailDiskCache.fileName("Lecture.pdf", key(sidecar = 1L))
        val v2 = ThumbnailDiskCache.fileName("Lecture.pdf", key(sidecar = 2L))
        val other = ThumbnailDiskCache.fileName("Other.pdf", key("Other.pdf"))
        assertNotEquals(v1, v2)
        assertEquals(v1.substringBefore('-'), v2.substringBefore('-'))
        assertNotEquals(v1.substringBefore('-'), other.substringBefore('-'))
    }

    @Test
    fun deleteForRemovesOnlyThatDocumentsThumbnails() {
        val dir = tmp.newFolder("thumbs")
        val v1 = ThumbnailDiskCache.fileName("Lecture.pdf", key(sidecar = 1L))
        val v2 = ThumbnailDiskCache.fileName("Lecture.pdf", key(sidecar = 2L))
        val other = ThumbnailDiskCache.fileName("Other.pdf", key("Other.pdf"))
        listOf(v1, v2, other).forEach { File(dir, it).writeText("png") }

        ThumbnailDiskCache.deleteFor(dir, "Lecture.pdf", keep = v2)
        assertEquals(setOf(v2, other), dir.list()!!.toSet())

        ThumbnailDiskCache.deleteFor(dir, "Lecture.pdf")
        assertEquals(setOf(other), dir.list()!!.toSet())
    }

    @Test
    fun conversionMarkersAreGroupedPerDocument() {
        val dir = tmp.newFolder("markers")
        val marker = ThumbnailDiskCache.conversionMarkerName("Essay.docx", "Essay.docx|10|20")
        assertTrue(marker, Regex("[0-9a-f]{16}-[0-9a-f]{24}\\.converting").matches(marker))
        assertNotEquals(marker, ThumbnailDiskCache.conversionMarkerName("Essay.docx", "Essay.docx|10|21"))
        val other = ThumbnailDiskCache.conversionMarkerName("Other.docx", "Other.docx|10|20")
        listOf(marker, other).forEach { File(dir, it).writeText("1") }

        ThumbnailDiskCache.deleteFor(dir, "Essay.docx")

        assertEquals(setOf(other), dir.list()!!.toSet())
    }

    @Test
    fun markerAttemptsAreReadLeniently() {
        assertEquals(0, ThumbnailDiskCache.markerAttempts(null))
        assertEquals(0, ThumbnailDiskCache.markerAttempts(""))
        assertEquals(0, ThumbnailDiskCache.markerAttempts("garbage"))
        assertEquals(0, ThumbnailDiskCache.markerAttempts("-3"))
        assertEquals(2, ThumbnailDiskCache.markerAttempts(" 2\n"))
    }

    @Test
    fun pruneDropsTempFilesAndTheLeastRecentlyUsed() {
        val dir = tmp.newFolder("thumbs")
        val files = (1..5).map { i ->
            File(dir, "t$i.png").apply {
                writeText("png")
                setLastModified(1_700_000_000_000L + i * 1000L)
            }
        }
        File(dir, ThumbnailDiskCache.tempFileName("t6.png")).writeText("partial")

        ThumbnailDiskCache.prune(dir, maxFiles = 3)

        assertEquals(files.takeLast(3).map { it.name }.toSet(), dir.list()!!.toSet())
    }
}
