package com.studynotes.app.library.data

import com.studynotes.app.model.DocType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentSnifferTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val testdata = File("../testdata")

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    private fun zipWith(vararg entries: String): ByteArray = zipOf(entries.associateWith { "<x/>" })

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** A `_rels/.rels` whose officeDocument relationship points at [target]. */
    private fun rootRels(target: String, external: Boolean = false): String =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
          <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$target"${if (external) " TargetMode=\"External\"" else ""}/>
        </Relationships>"""

    private fun tempFile(bytes: ByteArray): File = tmp.newFile().apply { writeBytes(bytes) }

    /** Checks the file-based and the streaming detection agree on [zip]. */
    private fun assertWordPackage(expected: Boolean, zip: ByteArray) {
        assertEquals(expected, DocumentSniffer.zipHasWordDocument(ByteArrayInputStream(zip)))
        val file = tempFile(zip)
        assertEquals(expected, DocumentSniffer.zipHasWordDocument(file))
        assertEquals(if (expected) DocType.DOCX else null, DocumentSniffer.sniff(file))
    }

    @Test
    fun pdfHeaderIsRecognised() {
        assertEquals(ContentSignature.PDF, DocumentSniffer.signature("%PDF-1.7\n%âãÏÓ".toByteArray()))
    }

    @Test
    fun pdfHeaderAfterLeadingJunkIsRecognisedWithinTheLimit() {
        val nearStart = ByteArray(100) { ' '.code.toByte() } + "%PDF-1.4".toByteArray()
        assertEquals(ContentSignature.PDF, DocumentSniffer.signature(nearStart))
        val tooFar = ByteArray(DocumentSniffer.HEADER_BYTES) { ' '.code.toByte() } + "%PDF-1.4".toByteArray()
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature(tooFar))
    }

    @Test
    fun onlyTheGivenLengthIsExamined() {
        val head = "0123456789%PDF-1.4".toByteArray()
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature(head, length = 12))
        assertEquals(ContentSignature.PDF, DocumentSniffer.signature(head, length = head.size))
    }

    @Test
    fun containerSignatures() {
        assertEquals(ContentSignature.ZIP, DocumentSniffer.signature(bytes(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        assertEquals(
            ContentSignature.OLE,
            DocumentSniffer.signature(bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1, 0xB1, 0x1A, 0xE1, 0x00)),
        )
        // An empty-archive / spanned ZIP marker is not a document.
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature(bytes(0x50, 0x4B, 0x05, 0x06)))
    }

    @Test
    fun shortOrUnknownContentIsUnknown() {
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature(ByteArray(0)))
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature("%PD".toByteArray()))
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature("Hello, world".toByteArray()))
        assertEquals(ContentSignature.UNKNOWN, DocumentSniffer.signature(bytes(0x50, 0x4B)))
    }

    @Test
    fun minimalDocxHasAWordDocumentPart() {
        val docx = File(testdata, "Minimal.docx").readBytes()
        assertEquals(ContentSignature.ZIP, DocumentSniffer.signature(docx))
        assertTrue(DocumentSniffer.zipHasWordDocument(ByteArrayInputStream(docx)))
    }

    @Test
    fun corruptDocxIsRejected() {
        val corrupt = File(testdata, "Corrupt.docx").readBytes()
        assertEquals(ContentSignature.ZIP, DocumentSniffer.signature(corrupt))
        assertFalse(DocumentSniffer.zipHasWordDocument(ByteArrayInputStream(corrupt)))
        assertFalse(DocumentSniffer.zipHasWordDocument(File(testdata, "Corrupt.docx")))
    }

    @Test
    fun zipWithoutWordDocumentIsNotADocx() {
        val odtLike = zipWith("mimetype", "content.xml")
        assertFalse(DocumentSniffer.zipHasWordDocument(ByteArrayInputStream(odtLike)))
        val docxLike = zipWith("[Content_Types].xml", "word/document.xml")
        assertTrue(DocumentSniffer.zipHasWordDocument(ByteArrayInputStream(docxLike)))
    }

    @Test
    fun mainPartNamedByThePackageRelationshipsIsAccepted() {
        // Word for the web and some mobile apps save the main part as word/document2.xml.
        assertWordPackage(
            true,
            zipOf(
                mapOf(
                    "[Content_Types].xml" to "<Types/>",
                    "_rels/.rels" to rootRels("/word/document2.xml"),
                    "word/document2.xml" to "<w:document/>",
                ),
            ),
        )
        // A relative target, resolved from the package root.
        assertWordPackage(
            true,
            zipOf(mapOf("_rels/.rels" to rootRels("word/main.xml"), "word/main.xml" to "<w:document/>")),
        )
    }

    @Test
    fun partNamesMatchCaseInsensitively() {
        assertWordPackage(true, zipWith("[Content_Types].xml", "Word/Document.xml"))
        assertWordPackage(
            true,
            zipOf(mapOf("_RELS/.RELS" to rootRels("/word/document2.xml"), "WORD/DOCUMENT2.XML" to "<w:document/>")),
        )
    }

    @Test
    fun relationshipsToMissingOrExternalPartsDontMakeADocx() {
        assertWordPackage(false, zipOf(mapOf("_rels/.rels" to rootRels("/word/document2.xml"), "word/other.xml" to "<x/>")))
        assertWordPackage(
            false,
            zipOf(mapOf("_rels/.rels" to rootRels("word/document2.xml", external = true), "word/document2.xml" to "<x/>")),
        )
        // Unreadable relationships fall back to the usual part name.
        assertWordPackage(false, zipOf(mapOf("_rels/.rels" to "not xml <<", "content.xml" to "<x/>")))
        assertWordPackage(true, zipOf(mapOf("_rels/.rels" to "not xml <<", "word/document.xml" to "<x/>")))
    }

    @Test
    fun fixturesAreSniffedByContent() {
        assertEquals(DocType.PDF, DocumentSniffer.sniff(File(testdata, "Biology Lecture Notes.pdf")))
        assertEquals(DocType.PDF, DocumentSniffer.sniff(File(testdata, "Password Protected.pdf")))
        assertEquals(DocType.DOCX, DocumentSniffer.sniff(File(testdata, "Minimal.docx")))
        assertEquals(DocType.DOCX, DocumentSniffer.sniff(File(testdata, "Cell Biology Study Guide.docx")))
        assertNull(DocumentSniffer.sniff(File(testdata, "Corrupt.docx")))
        assertNull(DocumentSniffer.sniff(File(testdata, "does not exist.pdf")))
    }
}
