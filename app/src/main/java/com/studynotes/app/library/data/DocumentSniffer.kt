package com.studynotes.app.library.data

import com.studynotes.app.docx.DocxParser
import com.studynotes.app.docx.attr
import com.studynotes.app.model.DocType
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/** What the first bytes of a file say about its container format. */
enum class ContentSignature {
    /** "%PDF-" within the first [DocumentSniffer.HEADER_BYTES] bytes. */
    PDF,

    /** A ZIP local file header (DOCX, but also ODT, Pages, EPUB, plain .zip ...). */
    ZIP,

    /** An OLE compound file: legacy .doc, or a password-protected Office document. */
    OLE,
    UNKNOWN,
}

/**
 * Decides a file's type from its content rather than its name or the MIME type a provider reports
 * (both are often wrong or missing for files coming from cloud drives, email and chat apps).
 */
object DocumentSniffer {
    /** How far into a file the PDF header may appear (PDFium accepts leading junk up to here). */
    const val HEADER_BYTES = 1024

    /** The usual main part of a Word document (the fallback when the package doesn't name another). */
    const val WORD_DOCUMENT_ENTRY = "word/document.xml"

    /** The package relationships part, which names the main document part. */
    private const val ROOT_RELATIONSHIPS_ENTRY = "_rels/.rels"

    /** The root relationships part is a few hundred bytes; a bigger one is ignored, not inflated. */
    private const val MAX_RELATIONSHIPS_BYTES = 256 * 1024

    private const val OFFICE_DOCUMENT_TYPE_SUFFIX = "/officeDocument"
    private const val RELATIONSHIP_ELEMENT = "Relationship"

    private val PDF_MAGIC = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D) // %PDF-
    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04) // PK\3\4
    private val OLE_MAGIC = byteArrayOf(
        0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
    )

    /** Classifies the first [length] bytes of [head]. */
    fun signature(head: ByteArray, length: Int = head.size): ContentSignature {
        val n = length.coerceIn(0, head.size)
        return when {
            startsWith(head, n, ZIP_MAGIC) -> ContentSignature.ZIP
            startsWith(head, n, OLE_MAGIC) -> ContentSignature.OLE
            indexOf(head, minOf(n, HEADER_BYTES), PDF_MAGIC) >= 0 -> ContentSignature.PDF
            else -> ContentSignature.UNKNOWN
        }
    }

    /** [signature] of the start of [file]; UNKNOWN if it can't be read. */
    fun signature(file: File): ContentSignature {
        val head = ByteArray(HEADER_BYTES)
        val read = try {
            file.inputStream().use { it.readUpTo(head) }
        } catch (e: IOException) {
            return ContentSignature.UNKNOWN
        }
        return signature(head, read)
    }

    /** The document type of [file] judged by content, or null if it is neither a PDF nor a DOCX. */
    fun sniff(file: File): DocType? = when (signature(file)) {
        ContentSignature.PDF -> DocType.PDF
        ContentSignature.ZIP -> if (zipHasWordDocument(file)) DocType.DOCX else null
        else -> null
    }

    /**
     * True when the ZIP [file] is a Word package, judged the way the DOCX converter opens it: the
     * main part named by the officeDocument relationship in `_rels/.rels` (some producers, e.g. Word
     * for the web, call it word/document2.xml) or else [WORD_DOCUMENT_ENTRY] must exist, with entry
     * names matched case-insensitively. Reads the central directory and at most the small
     * relationships part, so it is cheap even for large files; a truncated or corrupt archive yields
     * false.
     */
    fun zipHasWordDocument(file: File): Boolean = try {
        ZipFile(file).use { zip ->
            if (zip.getEntry(WORD_DOCUMENT_ENTRY) != null) return@use true
            val names = HashSet<String>()
            var relationships: ByteArray? = null
            for (entry in zip.entries()) {
                if (entry.isDirectory) continue
                names += lower(entry.name)
                if (relationships == null && entry.name.equals(ROOT_RELATIONSHIPS_ENTRY, ignoreCase = true)) {
                    relationships = zip.getInputStream(entry).use(::readLimited)
                }
            }
            hasMainPart(names, relationships)
        }
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        // Malformed entry names, or Android's zip path validation rejecting "../" entries.
        false
    }

    /** Streaming variant of [zipHasWordDocument] for data that isn't in a file. Does not close [input]. */
    fun zipHasWordDocument(input: InputStream): Boolean = try {
        val zip = ZipInputStream(input)
        val names = HashSet<String>()
        var relationships: ByteArray? = null
        for (entry in generateSequence { zip.nextEntry }) {
            if (entry.isDirectory) continue
            names += lower(entry.name)
            if (relationships == null && entry.name.equals(ROOT_RELATIONSHIPS_ENTRY, ignoreCase = true)) {
                relationships = readLimited(zip)
            }
        }
        hasMainPart(names, relationships)
    } catch (e: IOException) {
        false
    } catch (e: RuntimeException) {
        false
    }

    /** [lowerNames]: the package's entry names, lower-cased. [relationships]: the `_rels/.rels` bytes. */
    private fun hasMainPart(lowerNames: Set<String>, relationships: ByteArray?): Boolean {
        val target = relationships?.let(::officeDocumentTarget)
        if (target != null && lower(target) in lowerNames) return true
        return lower(WORD_DOCUMENT_ENTRY) in lowerNames
    }

    /**
     * The part name the package's officeDocument relationship points to (resolved the way the
     * converter does: from the package root, a leading '/' dropped), or null if there is none or
     * the XML can't be read.
     */
    private fun officeDocumentTarget(relationships: ByteArray): String? = try {
        val xp = XmlPullParserFactory.newInstance().apply { isNamespaceAware = true }.newPullParser()
        xp.setInput(relationships.inputStream(), null)
        var found: String? = null
        while (found == null) {
            val event = xp.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            if (event != XmlPullParser.START_TAG || xp.name != RELATIONSHIP_ELEMENT) continue
            val type = xp.attr("Type").orEmpty()
            val target = xp.attr("Target")
            val external = xp.attr("TargetMode").equals("External", ignoreCase = true)
            if (!external && target != null && type.endsWith(OFFICE_DOCUMENT_TYPE_SUFFIX)) {
                found = DocxParser.resolvePartName("", target)
            }
        }
        found?.takeIf { it.isNotEmpty() }
    } catch (e: XmlPullParserException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    /** Reads [input] to the end if it holds at most [MAX_RELATIONSHIPS_BYTES], else returns null. */
    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            if (out.size() + n > MAX_RELATIONSHIPS_BYTES) return null
            out.write(buffer, 0, n)
        }
    }

    private fun lower(name: String) = name.lowercase(Locale.ROOT)

    private fun startsWith(data: ByteArray, length: Int, prefix: ByteArray): Boolean {
        if (length < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private fun indexOf(data: ByteArray, length: Int, needle: ByteArray): Int {
        outer@ for (start in 0..length - needle.size) {
            for (i in needle.indices) if (data[start + i] != needle[i]) continue@outer
            return start
        }
        return -1
    }
}
