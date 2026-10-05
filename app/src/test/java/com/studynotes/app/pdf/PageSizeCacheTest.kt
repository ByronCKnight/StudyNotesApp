package com.studynotes.app.pdf

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PageSizeCacheTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val path = "/storage/emulated/0/Documents/StudyNotes/Biology Lecture Notes.pdf"
    private val stamp = FileStamp(length = 48_213L, lastModified = 1_700_000_000_000L)
    private val sizes = listOf(
        PageDimensions(612, 792),
        PageDimensions(792, 612), // landscape / rotated page
        PageDimensions(595, 842),
    )

    private fun cache(maxEntries: Int = 500) = PageSizeCache(File(temp.root, "pagesizes"), maxEntries)

    @Test
    fun roundTripReturnsTheWrittenSizes() {
        val cache = cache()
        assertTrue(cache.write(path, stamp, sizes))
        assertEquals(sizes, cache.read(path, stamp, pageCount = 3))
    }

    @Test
    fun separateInstancesShareTheDirectory() {
        assertTrue(cache().write(path, stamp, sizes))
        assertEquals(sizes, cache().read(path, stamp, pageCount = 3))
    }

    @Test
    fun missingEntryIsAMiss() {
        assertNull(cache().read(path, stamp, pageCount = 3))
    }

    @Test
    fun changedLengthInvalidates() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        assertNull(cache.read(path, stamp.copy(length = stamp.length + 1), pageCount = 3))
    }

    @Test
    fun changedLastModifiedInvalidates() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        assertNull(cache.read(path, stamp.copy(lastModified = stamp.lastModified + 1000), pageCount = 3))
    }

    @Test
    fun wrongPageCountInvalidates() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        assertNull(cache.read(path, stamp, pageCount = 2))
        assertNull(cache.read(path, stamp, pageCount = 4))
        assertNull(cache.read(path, stamp, pageCount = 0))
    }

    @Test
    fun otherPathIsAMiss() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        assertNull(cache.read("$path.copy", stamp, pageCount = 3))
    }

    @Test
    fun corruptJsonIsAMiss() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        cache.entryFile(path).writeText("{\"version\":1,\"path\":")
        assertNull(cache.read(path, stamp, pageCount = 3))

        cache.entryFile(path).writeText("")
        assertNull(cache.read(path, stamp, pageCount = 3))
    }

    @Test
    fun entryForAnotherPathUnderTheSameNameIsAMiss() {
        // Simulates a hash collision: the stored path must match, not just the file name.
        val cache = cache()
        cache.write("/somewhere/else.pdf", stamp, sizes)
        cache.entryFile("/somewhere/else.pdf").copyTo(cache.entryFile(path))
        assertNull(cache.read(path, stamp, pageCount = 3))
    }

    @Test
    fun nonPositiveOrMalformedSizesAreAMiss() {
        val cache = cache()
        fun entryWith(sizesJson: String) {
            val entry = cache.entryFile(path)
            entry.parentFile!!.mkdirs()
            entry.writeText(
                """{"version":1,"path":${JSONObject.quote(path)},"length":${stamp.length},""" +
                    """"lastModified":${stamp.lastModified},"sizes":$sizesJson}""",
            )
        }

        // Well-formed entry first, so the misses below are caused by the sizes alone.
        entryWith("[612,792,595,842]")
        assertEquals(listOf(PageDimensions(612, 792), PageDimensions(595, 842)), cache.read(path, stamp, pageCount = 2))

        entryWith("[612,792,0,792]")
        assertNull(cache.read(path, stamp, pageCount = 2))
        entryWith("[612,792,-5,792]")
        assertNull(cache.read(path, stamp, pageCount = 2))
        entryWith("[612,792,\"wide\",792]")
        assertNull(cache.read(path, stamp, pageCount = 2))
        entryWith("{\"a\":1}")
        assertNull(cache.read(path, stamp, pageCount = 2))
    }

    @Test
    fun unknownVersionIsAMiss() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        val entry = cache.entryFile(path)
        entry.writeText(JSONObject(entry.readText()).put("version", 2).toString())
        assertNull(cache.read(path, stamp, pageCount = 3))
    }

    @Test
    fun rewriteReplacesTheEntry() {
        val cache = cache()
        cache.write(path, stamp, sizes)
        val newStamp = stamp.copy(length = 1L)
        val newSizes = listOf(PageDimensions(300, 400))
        assertTrue(cache.write(path, newStamp, newSizes))
        assertEquals(newSizes, cache.read(path, newStamp, pageCount = 1))
        assertNull(cache.read(path, stamp, pageCount = 3))
        // Only the entry remains: the temp file was moved into place.
        assertEquals(listOf(cache.entryFile(path).name), cache.entryFile(path).parentFile!!.list()!!.toList())
    }

    @Test
    fun writeReportsFailureInsteadOfThrowing() {
        val blocker = temp.newFile("not-a-directory")
        val cache = PageSizeCache(blocker)
        assertFalse(cache.write(path, stamp, sizes))
        assertNull(cache.read(path, stamp, pageCount = 3))
    }

    @Test
    fun entryNamesAreStableAndFileSystemSafe() {
        val cache = cache()
        val name = cache.entryFile(path).name
        assertEquals(name, cache().entryFile(path).name)
        assertTrue(name.matches(Regex("[0-9a-f]{40}\\.json")))
        assertNotEquals(name, cache.entryFile("$path ").name)
    }

    @Test
    fun pruningDropsTheLeastRecentlyUsedEntries() {
        val cache = cache(maxEntries = 3)
        val paths = listOf("/a.pdf", "/b.pdf", "/c.pdf")
        paths.forEachIndexed { i, p ->
            cache.write(p, stamp, sizes)
            cache.entryFile(p).setLastModified(1_000_000L * (i + 1))
        }
        // Reading /a.pdf marks it as recently used, so /b.pdf is now the oldest.
        assertEquals(sizes, cache.read("/a.pdf", stamp, pageCount = 3))

        cache.write("/d.pdf", stamp, sizes)

        assertFalse(cache.entryFile("/b.pdf").exists())
        for (p in listOf("/a.pdf", "/c.pdf", "/d.pdf")) {
            assertEquals(p, sizes, cache.read(p, stamp, pageCount = 3))
        }
    }

    @Test
    fun fileStampReadsLengthAndModificationTime() {
        val file = temp.newFile("doc.pdf").apply {
            writeBytes(ByteArray(1234))
            setLastModified(1_650_000_000_000L)
        }
        val fileStamp = FileStamp.of(file)
        assertEquals(1234L, fileStamp.length)
        assertEquals(file.lastModified(), fileStamp.lastModified)
    }
}
