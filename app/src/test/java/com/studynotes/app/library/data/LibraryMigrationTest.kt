package com.studynotes.app.library.data

import com.studynotes.app.data.LibraryPaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryMigrationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var from: LibraryPaths
    private lateinit var to: LibraryPaths

    @Before
    fun setUp() {
        from = LibraryPaths(File(tmp.root, "private/StudyNotes"), isPublic = false)
        to = LibraryPaths(File(tmp.root, "public/StudyNotes"), isPublic = true)
        assertTrue(from.ensureDirs())
    }

    private fun File.write(text: String) = apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun backup(paths: LibraryPaths, fileName: String) = File(paths.annotationsDir, "$fileName.json.bak")

    @Test
    fun documentsAndTheirDataMove() {
        val pdf = File(from.root, "A.pdf").write("pdf A")
        pdf.setLastModified(1_600_000_000_000L)
        from.sidecarFile("A.pdf").write("notes A")
        backup(from, "A.pdf").write("old notes A")
        File(from.root, "B.docx").write("docx B")
        from.renditionFile("B.docx").write("rendition B")
        from.renditionMetaFile("B.docx").write("meta B")

        val result = LibraryMigration.migrate(from, to)

        assertTrue(result.failed.isEmpty())
        assertEquals(setOf("A.pdf", "B.docx"), result.moved.map { it.newFile.name }.toSet())
        assertEquals("pdf A", File(to.root, "A.pdf").readText())
        assertEquals(1_600_000_000_000L, File(to.root, "A.pdf").lastModified())
        assertEquals("notes A", to.sidecarFile("A.pdf").readText())
        assertEquals("old notes A", backup(to, "A.pdf").readText())
        assertEquals("rendition B", to.renditionFile("B.docx").readText())
        assertEquals("meta B", to.renditionMetaFile("B.docx").readText())
        assertTrue(LibraryMigration.documentsIn(from.root).isEmpty())
        assertFalse("emptied metadata folder is removed", from.metaDir.exists())
    }

    @Test
    fun nameClashGetsACounterAndTheDataFollows() {
        File(to.root.apply { mkdirs() }, "A.pdf").write("public A")
        to.sidecarFile("A.pdf").write("public notes")
        File(from.root, "a.PDF").write("private A")
        from.sidecarFile("a.PDF").write("private notes")

        val result = LibraryMigration.migrate(from, to)

        assertEquals(listOf(MigratedDocument("a.PDF", File(to.root, "a (2).PDF"))), result.moved)
        assertEquals("public A", File(to.root, "A.pdf").readText())
        assertEquals("public notes", to.sidecarFile("A.pdf").readText())
        assertEquals("private A", File(to.root, "a (2).PDF").readText())
        assertEquals("private notes", to.sidecarFile("a (2).PDF").readText())
    }

    @Test
    fun orphanedDataInTheTargetIsNeverOverwritten() {
        to.sidecarFile("C.pdf").write("orphan notes")
        File(from.root, "C.pdf").write("private C")
        from.sidecarFile("C.pdf").write("private notes")

        val result = LibraryMigration.migrate(from, to)

        assertEquals("C (2).pdf", result.moved.single().newFile.name)
        assertEquals("orphan notes", to.sidecarFile("C.pdf").readText())
        assertEquals("private notes", to.sidecarFile("C (2).pdf").readText())
    }

    @Test
    fun leftoverDataMovesOnceNoDocumentRemains() {
        File(from.root, "D.pdf").write("D")
        from.sidecarFile("Gone.pdf").write("orphan of a deleted document")
        from.sidecarFile("Clash.pdf").write("private orphan")
        to.sidecarFile("Clash.pdf").write("public orphan")

        LibraryMigration.migrate(from, to)

        assertEquals("orphan of a deleted document", to.sidecarFile("Gone.pdf").readText())
        assertFalse(from.sidecarFile("Gone.pdf").exists())
        assertEquals("public orphan", to.sidecarFile("Clash.pdf").readText())
        assertEquals("the clashing file stays where it was", "private orphan", from.sidecarFile("Clash.pdf").readText())
    }

    @Test
    fun unusableTargetLeavesEverythingInPlace() {
        File(tmp.root, "public").mkdirs()
        to.root.writeText("not a folder")
        File(from.root, "E.pdf").write("E")
        from.sidecarFile("E.pdf").write("notes E")

        val result = LibraryMigration.migrate(from, to)

        assertEquals(listOf("E.pdf"), result.failed)
        assertTrue(result.moved.isEmpty())
        assertEquals("E", File(from.root, "E.pdf").readText())
        assertEquals("notes E", from.sidecarFile("E.pdf").readText())
    }

    @Test
    fun onlyVisibleDocumentsAreMigrated() {
        File(from.root, "Doc.pdf").write("doc")
        File(from.root, ".Hidden.pdf").write("hidden")
        File(from.root, ".Import.pdf.part").write("partial")
        File(from.root, "readme.txt").write("text")
        File(from.root, "Folder.pdf").mkdirs()

        assertEquals(listOf("Doc.pdf"), LibraryMigration.documentsIn(from.root).map { it.name })
        assertEquals(listOf("Doc.pdf"), LibraryMigration.migrate(from, to).moved.map { it.newFile.name })
        assertTrue(File(from.root, "readme.txt").exists())
    }
}
