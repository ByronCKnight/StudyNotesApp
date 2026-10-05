package com.studynotes.app.library.data

import com.studynotes.app.data.LibraryPaths
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LibraryImporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var root: File

    private val pdf = "%PDF-1.4\n1 0 obj << >> endobj\n%%EOF\n".toByteArray()
    private val otherPdf = "%PDF-1.7\n% a different document\n%%EOF\n".toByteArray()
    private val docx = File("../testdata/Minimal.docx").readBytes()
    private val ole = ByteArray(64).also {
        byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            .copyInto(it)
    }

    @Before
    fun setUp() {
        root = tmp.newFolder("library")
    }

    /** Runs both import steps the way the repository does. */
    private fun import(displayName: String?, bytes: ByteArray): StoredDocument {
        val part = File(root, LibraryFileNames.partFileName(displayName))
        LibraryImporter.download(ByteArrayInputStream(bytes), part) {}
        return LibraryImporter.finalize(part, root, displayName)
    }

    private fun problemOf(block: () -> Unit): ImportProblem {
        try {
            block()
        } catch (e: ImportException) {
            return e.problem
        }
        fail("Expected an ImportException")
        throw AssertionError()
    }

    private fun visibleNames() = root.list()!!.filterNot { it.startsWith(".") }.sorted()

    private fun leftoverTempFiles() = root.list()!!.filter { it.startsWith(".") }

    @Test
    fun downloadWritesTheWholeStream() {
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val part = File(root, ".big.part")
        assertEquals(big.size.toLong(), LibraryImporter.download(ByteArrayInputStream(big), part) {})
        assertArrayEquals(big, part.readBytes())
    }

    @Test
    fun emptyDownloadIsRejectedAndCleanedUp() {
        val part = File(root, ".empty.part")
        assertEquals(ImportProblem.EMPTY_FILE, problemOf { LibraryImporter.download(ByteArrayInputStream(ByteArray(0)), part) {} })
        assertFalse(part.exists())
    }

    @Test
    fun readFailureIsReportedAndCleanedUp() {
        val failing = object : InputStream() {
            private var served = 0
            override fun read(): Int = throw IOException("connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (served > 0) throw IOException("connection reset")
                served = len
                return len
            }
        }
        val part = File(root, ".net.part")
        assertEquals(ImportProblem.READ_FAILED, problemOf { LibraryImporter.download(failing, part) {} })
        assertFalse(part.exists())
    }

    @Test
    fun cancellationDeletesThePartFile() {
        val part = File(root, ".cancel.part")
        val cancelled = IllegalStateException("cancelled")
        try {
            LibraryImporter.download(ByteArrayInputStream(pdf), part) { throw cancelled }
            fail("Expected the cancellation to propagate")
        } catch (e: IllegalStateException) {
            assertTrue(e === cancelled)
        }
        assertFalse(part.exists())
    }

    @Test
    fun pdfGetsItsNameAndExtensionFromTheSource() {
        val stored = import("Lecture", pdf)
        assertEquals(File(root, "Lecture.pdf"), stored.file)
        assertFalse(stored.reused)
        assertArrayEquals(pdf, stored.file.readBytes())
        assertTrue(leftoverTempFiles().isEmpty())
    }

    @Test
    fun extensionComesFromTheContentNotTheName() {
        assertEquals("Report.docx", import("Report.pdf", docx).file.name)
        assertEquals("Scan.pdf", import("Scan.docx", pdf).file.name)
    }

    @Test
    fun nameClashesGetACounterCaseInsensitively() {
        File(root, "lecture.PDF").writeBytes(otherPdf)
        val stored = import("Lecture.pdf", pdf)
        assertEquals("Lecture (2).pdf", stored.file.name)
        assertEquals(listOf("Lecture (2).pdf", "lecture.PDF"), visibleNames())
    }

    @Test
    fun identicalFileIsReusedInsteadOfDuplicated() {
        val existing = File(root, "Already here.pdf").apply { writeBytes(pdf) }
        val stored = import("Lecture.pdf", pdf)
        assertTrue(stored.reused)
        assertEquals(existing, stored.file)
        assertEquals(listOf("Already here.pdf"), visibleNames())
        assertTrue(leftoverTempFiles().isEmpty())
    }

    @Test
    fun sameSizeDifferentBytesIsNotReused() {
        val sameSize = pdf.copyOf().also { it[it.size - 2] = 'X'.code.toByte() }
        File(root, "Lecture.pdf").writeBytes(sameSize)
        val stored = import("Lecture.pdf", pdf)
        assertFalse(stored.reused)
        assertEquals("Lecture (2).pdf", stored.file.name)
    }

    @Test
    fun aNameWithLeftoverDataOfAnotherDocumentIsNotReused() {
        // "Homework 1.pdf" was annotated, then deleted in the Files app: its data stayed behind.
        val paths = LibraryPaths(root, isPublic = true)
        assertTrue(paths.ensureDirs())
        paths.sidecarFile("Homework 1.pdf").writeText("{}")
        File(paths.annotationsDir, "Homework 2.pdf.json.bak").writeText("{}")
        paths.renditionFile("Homework 3.pdf").writeBytes(otherPdf) // an unlocked copy
        val hasLeftovers = { name: String -> DocumentMetadata.exists(paths, name) }

        fun importWithLeftovers(name: String): StoredDocument {
            val part = File(root, LibraryFileNames.partFileName(name))
            LibraryImporter.download(ByteArrayInputStream(pdf + name.toByteArray()), part) {}
            return LibraryImporter.finalize(part, root, name, hasLeftovers)
        }

        assertEquals("Homework 1 (2).pdf", importWithLeftovers("Homework 1.pdf").file.name)
        assertEquals("Homework 2 (2).pdf", importWithLeftovers("Homework 2.pdf").file.name)
        assertEquals("Homework 3 (2).pdf", importWithLeftovers("Homework 3.pdf").file.name)
        assertEquals("Homework 4.pdf", importWithLeftovers("Homework 4.pdf").file.name)
        // The leftovers were not touched.
        assertTrue(paths.sidecarFile("Homework 1.pdf").isFile)
        assertFalse(DocumentMetadata.exists(paths, "Homework 1 (2).pdf"))
    }

    @Test
    fun docxWhoseMainPartIsNotDocumentXmlIsImported() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(
                ("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/" +
                    "relationships/officeDocument\" Target=\"/word/document2.xml\"/></Relationships>").toByteArray(),
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document2.xml"))
            zip.write("<w:document/>".toByteArray())
            zip.closeEntry()
        }
        assertEquals("Essay.docx", import("Essay.docx", out.toByteArray()).file.name)
    }

    @Test
    fun unsupportedContentIsRejectedAndCleanedUp() {
        assertEquals(ImportProblem.UNSUPPORTED_TYPE, problemOf { import("Lecture.pages", "PK\u0003\u0004junk".toByteArray()) })
        assertEquals(ImportProblem.UNSUPPORTED_TYPE, problemOf { import("notes.txt", "hello".toByteArray()) })
        assertEquals(
            ImportProblem.UNSUPPORTED_TYPE,
            problemOf { import("Corrupt.docx", File("../testdata/Corrupt.docx").readBytes()) },
        )
        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun legacyAndProtectedWordFilesGetSpecificReasons() {
        assertEquals(ImportProblem.OLD_WORD_FORMAT, problemOf { import("Essay.doc", ole) })
        assertEquals(ImportProblem.PROTECTED_WORD_FILE, problemOf { import("Secret.docx", ole) })
        assertEquals(ImportProblem.UNSUPPORTED_TYPE, problemOf { import("Budget.xls", ole) })
    }

    @Test
    fun failureTextsAreShortAndHuman() {
        assertEquals(
            "Lecture.pages (unsupported file type)",
            LibraryImporter.failureText("Lecture.pages", ImportProblem.UNSUPPORTED_TYPE, remote = false),
        )
        assertEquals(
            "Report.pdf (couldn't download - check your connection)",
            LibraryImporter.failureText("Report.pdf", ImportProblem.READ_FAILED, remote = true),
        )
        assertEquals(
            "Report.pdf (couldn't read the file)",
            LibraryImporter.failureText("Report.pdf", ImportProblem.READ_FAILED, remote = false),
        )
        val long = LibraryImporter.failureText("x".repeat(200) + ".pdf", ImportProblem.NO_SPACE, remote = false)
        assertEquals("x".repeat(59) + "… (not enough storage space)", long)
    }
}
