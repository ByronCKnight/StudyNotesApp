package com.studynotes.app.data

import com.studynotes.app.data.UnlockedCopies.Meta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UnlockedCopiesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun recordedSourceMustMatchExactly() {
        val meta = Meta.Source(size = 1000, lastModified = 5000)
        assertTrue(UnlockedCopies.isCurrent(copyLastModified = 1, meta = meta, sourceSize = 1000, sourceLastModified = 5000))
        assertFalse(UnlockedCopies.isCurrent(copyLastModified = 9999, meta = meta, sourceSize = 1001, sourceLastModified = 5000))
        // A file copied in under the same name with an older timestamp is not the unlocked document.
        assertFalse(UnlockedCopies.isCurrent(copyLastModified = 9999, meta = meta, sourceSize = 1000, sourceLastModified = 4000))
    }

    @Test
    fun copiesWithoutARecordKeepTheEarlierRule() {
        assertTrue(UnlockedCopies.isCurrent(copyLastModified = 5000, meta = Meta.Missing, sourceSize = 1, sourceLastModified = 5000))
        assertFalse(UnlockedCopies.isCurrent(copyLastModified = 4999, meta = Meta.Missing, sourceSize = 1, sourceLastModified = 5000))
    }

    @Test
    fun unreadableRecordsAreNeverCurrent() {
        assertFalse(UnlockedCopies.isCurrent(copyLastModified = 9999, meta = Meta.Unreadable, sourceSize = 1, sourceLastModified = 1))
        assertEquals(Meta.Unreadable, UnlockedCopies.parseMeta("not json"))
        assertEquals(Meta.Unreadable, UnlockedCopies.parseMeta("{\"sourceSize\": 3}"))
        assertEquals(Meta.Source(3, 4), UnlockedCopies.parseMeta("{\"sourceSize\": 3, \"sourceLastModified\": 4}"))
    }

    @Test
    fun recordThenCurrentCopyOnDisk() {
        val paths = LibraryPaths(tmp.newFolder("lib"), isPublic = false)
        assertTrue(paths.ensureDirs())
        val source = File(paths.root, "Locked.pdf").apply { writeText("original") }
        source.setLastModified(1_000_000L)
        val copy = paths.renditionFile(source.name).apply { writeText("unlocked") }
        copy.setLastModified(500_000L) // older than the source: only the record can vouch for it

        assertNull(UnlockedCopies.currentCopy(paths, source))
        UnlockedCopies.record(paths, source.name, source.length(), source.lastModified())
        assertEquals(copy, UnlockedCopies.currentCopy(paths, source))

        // Replaced by another file of the same name.
        source.writeText("a different document")
        source.setLastModified(1_000_000L)
        assertNull(UnlockedCopies.currentCopy(paths, source))

        UnlockedCopies.clearRecord(paths, source.name)
        assertFalse(paths.renditionMetaFile(source.name).exists())
    }
}
