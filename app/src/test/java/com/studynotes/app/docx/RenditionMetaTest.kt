package com.studynotes.app.docx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenditionMetaTest {

    @Test
    fun roundTrip() {
        val meta = RenditionMeta(12345L, 1_700_000_000_000L, DocxConverter.VERSION, 1_800_000_000_000L)
        assertEquals(meta, RenditionMeta.decode(meta.encode()))
        assertFalse(meta.replacedOtherSource)

        val replaced = meta.copy(replacedSourceSize = 999L, replacedSourceLastModified = 42L)
        val decoded = RenditionMeta.decode(replaced.encode())
        assertEquals(replaced, decoded)
        assertTrue(decoded!!.replacedOtherSource)
    }

    @Test
    fun matchesOnSizeAndLastModified() {
        val meta = RenditionMeta(10L, 20L, 1, 0L)
        assertTrue(meta.matches(10L, 20L))
        assertFalse(meta.matches(11L, 20L))
        assertFalse(meta.matches(10L, 21L))
    }

    @Test
    fun unreadableMetaIsNull() {
        assertNull(RenditionMeta.decode(""))
        assertNull(RenditionMeta.decode("not json"))
        assertNull(RenditionMeta.decode("""{"converterVersion":1}"""))
    }

    @Test
    fun olderMetaWithoutOptionalFieldsStillDecodes() {
        val meta = RenditionMeta.decode("""{"sourceSize":5,"sourceLastModified":6}""")!!
        assertEquals(5L, meta.sourceSize)
        assertEquals(6L, meta.sourceLastModified)
        assertEquals(0, meta.converterVersion)
        assertFalse(meta.replacedOtherSource)
    }

    @Test
    fun fontKinds() {
        assertEquals(DocxFontKind.SANS, DocxFontKind.of(null))
        assertEquals(DocxFontKind.SANS, DocxFontKind.of("Calibri"))
        assertEquals(DocxFontKind.SANS, DocxFontKind.of("Microsoft Sans Serif"))
        assertEquals(DocxFontKind.SERIF, DocxFontKind.of("Times New Roman"))
        assertEquals(DocxFontKind.SERIF, DocxFontKind.of("Cambria"))
        assertEquals(DocxFontKind.SERIF, DocxFontKind.of("Georgia"))
        assertEquals(DocxFontKind.MONO, DocxFontKind.of("Courier New"))
        assertEquals(DocxFontKind.MONO, DocxFontKind.of("Consolas"))
    }
}
