package com.studynotes.app.data

import com.studynotes.app.model.AnnotationFile
import com.studynotes.app.model.DocType
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.InkTool
import com.studynotes.app.model.PageAnnotations
import com.studynotes.app.model.SourceInfo
import com.studynotes.app.model.Stroke
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AnnotationStoreTest {
    private lateinit var root: File
    private lateinit var paths: LibraryPaths
    private val store = AnnotationStore()

    @Before
    fun setUp() {
        root = Files.createTempDirectory("store-test").toFile()
        paths = LibraryPaths(root, isPublic = false)
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun fileWith(name: String, strokes: Int) = AnnotationFile(
        source = SourceInfo(name, DocType.PDF, 10L, 20L),
        savedAt = 30L,
        annotations = DocumentAnnotations(
            pages = mapOf(0 to PageAnnotations(strokes = (1..strokes).map {
                Stroke("s$it", InkTool.PEN, 0xFF000000.toInt(), 2f, floatArrayOf(it.toFloat(), 1f))
            })),
        ),
    )

    @Test
    fun missingSidecarIsReportedAsMissing() = runBlocking {
        assertEquals(SidecarLoad.Missing, store.loadResult(paths, "a.pdf"))
    }

    @Test
    fun saveThenLoadRoundTrips() = runBlocking {
        store.save(paths, fileWith("a.pdf", 3))
        val loaded = store.loadResult(paths, "a.pdf") as SidecarLoad.Loaded
        assertFalse(loaded.fromBackup)
        assertEquals(3, loaded.file.annotations.strokeCount)
    }

    @Test
    fun unreadableSidecarIsPreservedAndSurvivesLaterSaves() = runBlocking {
        paths.ensureDirs()
        val main = paths.sidecarFile("a.pdf")
        main.writeText("{ this is not json")
        val result = store.loadResult(paths, "a.pdf")
        assertTrue(result is SidecarLoad.Unreadable)
        val copies = (result as SidecarLoad.Unreadable).preservedCopies
        assertEquals(1, copies.size)

        // Two saves rotate main -> .bak twice; the preserved copy must outlive them.
        store.save(paths, fileWith("a.pdf", 1))
        store.save(paths, fileWith("a.pdf", 2))
        assertTrue(copies[0].isFile)
        assertEquals("{ this is not json", copies[0].readText())
    }

    @Test
    fun loadFallsBackToBackup() = runBlocking {
        store.save(paths, fileWith("a.pdf", 1))
        store.save(paths, fileWith("a.pdf", 2)) // previous version is now the .bak
        paths.sidecarFile("a.pdf").writeText("garbage")
        val loaded = store.loadResult(paths, "a.pdf") as SidecarLoad.Loaded
        assertTrue(loaded.fromBackup)
        assertEquals(1, loaded.file.annotations.strokeCount)
    }

    @Test
    fun renameMovesMainAndBackup() = runBlocking {
        store.save(paths, fileWith("a.pdf", 1))
        store.save(paths, fileWith("a.pdf", 2))
        store.rename(paths, "a.pdf", "b.pdf")
        assertFalse(paths.sidecarFile("a.pdf").exists())
        assertFalse(File(paths.annotationsDir, "a.pdf.json.bak").exists())
        assertTrue(paths.sidecarFile("b.pdf").isFile)
        assertTrue(File(paths.annotationsDir, "b.pdf.json.bak").isFile)
    }

    @Test
    fun renameKeepsABackupOnlySidecar() = runBlocking {
        store.save(paths, fileWith("a.pdf", 1))
        store.save(paths, fileWith("a.pdf", 2))
        paths.sidecarFile("a.pdf").delete() // interrupted save: only the .bak is left
        store.rename(paths, "a.pdf", "b.pdf")
        val loaded = store.loadResult(paths, "b.pdf") as SidecarLoad.Loaded
        assertEquals(1, loaded.file.annotations.strokeCount)
    }

    @Test
    fun loadWaitsForReservedSave() = runBlocking {
        store.save(paths, fileWith("a.pdf", 1))
        val reservation = store.reserveSave(paths, "a.pdf")
        val load = async { store.loadResult(paths, "a.pdf") }
        yield()
        assertFalse(load.isCompleted)
        store.save(paths, fileWith("a.pdf", 5))
        reservation.complete(Unit)
        val loaded = load.await() as SidecarLoad.Loaded
        assertEquals(5, loaded.file.annotations.strokeCount)
    }

    @Test
    fun damagedMainIsPreservedWhenLoadingFromBackup() = runBlocking {
        store.save(paths, fileWith("a.pdf", 1))
        store.save(paths, fileWith("a.pdf", 2))
        paths.sidecarFile("a.pdf").writeText("newer format")
        val loaded = store.loadResult(paths, "a.pdf") as SidecarLoad.Loaded
        assertTrue(loaded.fromBackup)
        assertTrue(loaded.unpreserved.isEmpty())

        // Two saves would rotate the damaged main file away; its preserved copy must remain.
        store.save(paths, fileWith("a.pdf", 3))
        store.save(paths, fileWith("a.pdf", 4))
        val kept = paths.annotationsDir.listFiles().orEmpty().filter { it.name.startsWith("a.pdf.json.unreadable-") }
        assertEquals(1, kept.size)
        assertEquals("newer format", kept[0].readText())
    }

    @Test
    fun ownReservationDoesNotBlockLoad() = runBlocking<Unit> {
        store.save(paths, fileWith("a.pdf", 2))
        val own = store.reserveSave(paths, "a.pdf")
        assertTrue(store.hasPendingSave(paths, "a.pdf"))
        val loaded = store.loadResult(paths, "a.pdf", ownReservation = own) as SidecarLoad.Loaded
        assertEquals(2, loaded.file.annotations.strokeCount)
        own.complete(Unit)
        assertFalse(store.hasPendingSave(paths, "a.pdf"))
    }

    @Test
    fun otherReservationStillBlocksLoadThatHasItsOwn() = runBlocking<Unit> {
        store.save(paths, fileWith("a.pdf", 1))
        val closing = store.reserveSave(paths, "a.pdf")
        val opening = store.reserveSave(paths, "a.pdf")
        val load = async { store.loadResult(paths, "a.pdf", ownReservation = opening) }
        yield()
        assertFalse(load.isCompleted)
        store.save(paths, fileWith("a.pdf", 7))
        closing.complete(Unit)
        assertEquals(7, (load.await() as SidecarLoad.Loaded).file.annotations.strokeCount)
        opening.complete(Unit)
    }
}
