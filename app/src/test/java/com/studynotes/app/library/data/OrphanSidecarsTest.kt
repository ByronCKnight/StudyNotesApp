package com.studynotes.app.library.data

import com.studynotes.app.data.LibraryPaths
import com.studynotes.app.model.AnnotationFile
import com.studynotes.app.model.AnnotationJson
import com.studynotes.app.model.DocType
import com.studynotes.app.model.DocumentAnnotations
import com.studynotes.app.model.SourceInfo
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OrphanSidecarsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var paths: LibraryPaths

    @Before
    fun setUp() {
        paths = LibraryPaths(File(tmp.root, "StudyNotes"), isPublic = true)
        paths.ensureDirs()
    }

    private fun doc(name: String, size: Long, lastModified: Long = 1_000L, type: DocType = DocType.PDF) =
        ListedDocument(name, type, size, lastModified)

    private fun orphan(name: String, size: Long, lastModified: Long = 1_000L, type: DocType = DocType.PDF) =
        OrphanedSidecar(name, SourceInfo(name, type, size, lastModified))

    private fun writeSidecar(fileName: String, size: Long, lastModified: Long = 1_000L, backupOnly: Boolean = false) {
        val json = AnnotationJson.encode(
            AnnotationFile(SourceInfo(fileName, DocType.fromFileName(fileName)!!, size, lastModified), 0L, DocumentAnnotations()),
        )
        val sidecar = paths.sidecarFile(fileName)
        (if (backupOnly) DocumentMetadata.backupOf(sidecar) else sidecar).writeText(json)
    }

    @Test
    fun orphanNamesAreSidecarsWithoutADocument() {
        val dir = paths.annotationsDir
        listOf(
            "Kept.pdf.json", "Kept.pdf.json.bak", // its document exists (different case)
            "Gone.pdf.json", "Gone.pdf.json.bak",
            "OnlyBackup.docx.json.bak",
            "Saving.pdf.json.tmp", "Damaged.pdf.json.unreadable-5", ".rename-123.pdf.json", "notes.txt.json",
        ).forEach { File(dir, it).writeText("{}") }

        val names = OrphanSidecars.orphanNames(dir, setOf(LibraryFileNames.nameKey("kept.PDF")))

        assertEquals(listOf("Gone.pdf", "OnlyBackup.docx"), names)
    }

    @Test
    fun aUniqueTypeAndSizeMatchReattaches() {
        val matches = OrphanSidecars.match(
            documents = listOf(doc("Lecture 3 - Cells.pdf", 5_000), doc("Other.pdf", 7_000)),
            orphans = listOf(orphan("lec3.pdf", 5_000)),
        )
        assertEquals(mapOf("lec3.pdf" to "Lecture 3 - Cells.pdf"), matches)
    }

    @Test
    fun typeMustMatchToo() {
        val matches = OrphanSidecars.match(
            documents = listOf(doc("Essay.docx", 5_000, type = DocType.DOCX)),
            orphans = listOf(orphan("Essay.pdf", 5_000)),
        )
        assertEquals(emptyMap<String, String>(), matches)
    }

    @Test
    fun ambiguousMatchesAreLeftAlone() {
        // Two documents of the same size: nothing tells them apart.
        assertEquals(
            emptyMap<String, String>(),
            OrphanSidecars.match(listOf(doc("A.pdf", 5_000), doc("B.pdf", 5_000)), listOf(orphan("old.pdf", 5_000))),
        )
        // Two orphans for one document.
        assertEquals(
            emptyMap<String, String>(),
            OrphanSidecars.match(listOf(doc("A.pdf", 5_000)), listOf(orphan("x.pdf", 5_000), orphan("y.pdf", 5_000))),
        )
    }

    @Test
    fun theModificationTimeSettlesSeveralCandidates() {
        val matches = OrphanSidecars.match(
            documents = listOf(doc("A.pdf", 5_000, lastModified = 1L), doc("B.pdf", 5_000, lastModified = 2L)),
            orphans = listOf(orphan("old B.pdf", 5_000, lastModified = 2L), orphan("other.pdf", 5_000, lastModified = 3L)),
        )
        assertEquals(mapOf("old B.pdf" to "B.pdf"), matches)
    }

    @Test
    fun reattachmentsReadTheRecordedSourceOfOrphans() {
        writeSidecar("lec3.pdf", size = 5_000)
        writeSidecar("Annotated.pdf", size = 5_000) // belongs to a document that is still there
        writeSidecar("old handout.docx", size = 900, backupOnly = true)
        val documents = listOf(
            doc("Lecture 3 - Cells.pdf", 5_000),
            doc("Annotated.pdf", 5_000),
            doc("Handout.docx", 900, type = DocType.DOCX),
        )

        val matches = OrphanSidecars().reattachments(paths, documents)

        // "Annotated.pdf" has its own sidecar, so only "Lecture 3 - Cells.pdf" is a candidate.
        assertEquals(mapOf("lec3.pdf" to "Lecture 3 - Cells.pdf", "old handout.docx" to "Handout.docx"), matches)
    }

    @Test
    fun unreadableOrphansAreIgnored() {
        paths.sidecarFile("lec3.pdf").writeText("not json")
        assertEquals(emptyMap<String, String>(), OrphanSidecars().reattachments(paths, listOf(doc("New.pdf", 8))))
    }

    @Test
    fun aChangedOrphanIsReadAgain() {
        val finder = OrphanSidecars()
        writeSidecar("lec3.pdf", size = 1_234)
        assertEquals(emptyMap<String, String>(), finder.reattachments(paths, listOf(doc("New.pdf", 5_000))))

        writeSidecar("lec3.pdf", size = 5_000)
        paths.sidecarFile("lec3.pdf").setLastModified(1_700_000_000_000L)
        assertEquals(mapOf("lec3.pdf" to "New.pdf"), finder.reattachments(paths, listOf(doc("New.pdf", 5_000))))
    }
}
