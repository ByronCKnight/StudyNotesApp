package com.studynotes.app.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationJsonTest {

    private fun sample(): AnnotationFile {
        val hl = Stroke("s1", InkTool.HIGHLIGHTER, 0xFFFFE94D.toInt(), 14f, floatArrayOf(10f, 20.5f, 110.25f, 20.5f))
        val snapped = Stroke("s2", InkTool.HIGHLIGHTER, 0xFFA8F0A0.toInt(), 11f, floatArrayOf(1f, 2f, 3f, 2f), flatCaps = true, groupId = "g1")
        val pen = Stroke("s3", InkTool.PEN, 0xFF1F1F24.toInt(), 2f, floatArrayOf(5f, 5f))
        val note = Note("n1", 40f, 60f, "Remember: \"quotes\", ünïcödé, emoji 📚\nline 2", 0xFFFFF3A6.toInt(), 1000L, 2000L)
        val ann = DocumentAnnotations(
            pages = mapOf(
                0 to PageAnnotations(strokes = listOf(hl, snapped), notes = listOf(note)),
                7 to PageAnnotations(strokes = listOf(pen)),
            ),
            viewState = ViewState(page = 7, pageOffsetFraction = 0.25f, zoom = 1.5f),
        )
        return AnnotationFile(SourceInfo("Lecture 1.pdf", DocType.PDF, 12345L, 999L), 5000L, ann)
    }

    @Test
    fun roundTripPreservesEverything() {
        val original = sample()
        val decoded = AnnotationJson.decode(AnnotationJson.encode(original))
        assertEquals(original.source, decoded.source)
        assertEquals(original.savedAt, decoded.savedAt)
        assertEquals(original.annotations.viewState, decoded.annotations.viewState)
        assertEquals(original.annotations.pages, decoded.annotations.pages)
    }

    @Test
    fun malformedItemsAreSkippedNotFatal() {
        val json = """
            {"format":"studynotes.annotations","version":1,
             "source":{"fileName":"a.docx","type":"docx","size":1,"lastModified":2},
             "pages":[{"index":2,"strokes":[
                {"id":"ok","tool":"pen","color":"#FF000000","width":2,"points":[1,2,3,4]},
                {"id":"bad-odd","tool":"pen","color":"#FF000000","width":2,"points":[1,2,3]},
                {"id":"bad-tool","tool":"laser","color":"#FF000000","width":2,"points":[1,2]},
                {"id":"bad-width","tool":"pen","color":"#FF000000","width":-1,"points":[1,2]}
             ],"notes":[{"id":"n","x":"nope","y":1,"text":"t"}]}]}
        """.trimIndent()
        val decoded = AnnotationJson.decode(json)
        assertEquals(DocType.DOCX, decoded.source.type)
        val page = decoded.annotations.page(2)
        assertEquals(listOf("ok"), page.strokes.map { it.id })
        assertTrue(page.notes.isEmpty())
    }

    @Test(expected = AnnotationFormatException::class)
    fun rejectsForeignJson() {
        AnnotationJson.decode("""{"hello":"world"}""")
    }

    @Test
    fun colorParsing() {
        assertEquals(0xFF112233.toInt(), AnnotationJson.parseColor("#112233"))
        assertEquals(0x80112233.toInt(), AnnotationJson.parseColor("#80112233"))
        assertNull(AnnotationJson.parseColor("112233"))
        assertEquals("#FFFFE94D", AnnotationJson.colorToHex(0xFFFFE94D.toInt()))
    }

    @Test
    fun updatePageDropsEmptyPages() {
        val ann = sample().annotations
        val cleared = ann.updatePage(7) { PageAnnotations.EMPTY }
        assertTrue(7 !in cleared.pages)
        assertEquals(3, ann.strokeCount)
        assertEquals(2, cleared.strokeCount)
    }
}
