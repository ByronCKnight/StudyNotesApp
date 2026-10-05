package com.studynotes.app.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class ExportFilesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun partFileSitsNextToTarget() {
        val target = File(tmp.root, "A (annotated).pdf")
        val part = ExportFiles.partFileFor(target)
        assertEquals(tmp.root.absoluteFile, part.parentFile)
        assertEquals("A (annotated).pdf.part", part.name)
    }

    @Test
    fun moveIntoPlaceReplacesStaleTarget() {
        val target = File(tmp.root, "out.pdf").apply { writeText("old and longer content") }
        val part = File(tmp.root, "out.pdf.part").apply { writeText("new") }
        ExportFiles.moveIntoPlace(part, target)
        assertEquals("new", target.readText())
        assertFalse(part.exists())
    }

    @Test
    fun moveIntoPlaceCreatesTarget() {
        val target = File(tmp.root, "fresh.pdf")
        val part = File(tmp.root, "fresh.pdf.part").apply { writeText("data") }
        ExportFiles.moveIntoPlace(part, target)
        assertEquals("data", target.readText())
    }

    @Test
    fun pruneDeletesOnlyOldFilesOtherThanTheOneBeingWritten() {
        val now = System.currentTimeMillis()
        val old = TimeUnit.HOURS.toMillis(25)
        val oldExport = File(tmp.root, "old (annotated).pdf").apply { writeText("x"); setLastModified(now - old) }
        val oldPart = File(tmp.root, "crashed.pdf.part").apply { writeText("x"); setLastModified(now - old) }
        val recent = File(tmp.root, "recent (annotated).pdf").apply { writeText("x"); setLastModified(now - 1_000) }
        val keep = File(tmp.root, "same (annotated).pdf").apply { writeText("x"); setLastModified(now - old) }

        ExportFiles.pruneExports(tmp.root, keep = File(tmp.root, "same (annotated).pdf"), now = now)

        assertFalse(oldExport.exists())
        assertFalse(oldPart.exists())
        assertTrue(recent.exists())
        assertTrue(keep.exists())
    }

    @Test
    fun pruneToleratesMissingDirectory() {
        val missing = File(tmp.root, "nope")
        ExportFiles.pruneExports(missing, keep = File(missing, "a.pdf"), now = System.currentTimeMillis())
        assertFalse(missing.exists())
    }

    // ---- Per-document export folders (export-4)

    @Test
    fun documentsWithTheSameTitleExportToDifferentFiles() {
        val pdf = ExportFiles.exportTargetFor(tmp.root, "Lecture 3.pdf")
        val docx = ExportFiles.exportTargetFor(tmp.root, "Lecture 3.docx")
        assertEquals("Lecture 3 (annotated).pdf", pdf.name)
        assertEquals("Lecture 3 (annotated).pdf", docx.name)
        assertNotEquals(pdf.parentFile, docx.parentFile)
        assertEquals(tmp.root, pdf.parentFile?.parentFile)
    }

    @Test
    fun anExportWithoutMarksIsNotCalledAnnotated() {
        val plain = ExportFiles.exportTargetFor(tmp.root, "Lecture 3.docx", annotated = false)
        assertEquals("Lecture 3.pdf", plain.name)
        assertEquals(ExportFiles.exportTargetFor(tmp.root, "Lecture 3.docx").parentFile, plain.parentFile)
    }

    @Test
    fun titlesThatSanitiseAlikeExportToDifferentFiles() {
        val a = ExportFiles.exportTargetFor(tmp.root, "A:B.pdf")
        val b = ExportFiles.exportTargetFor(tmp.root, "A_B.pdf")
        assertEquals(a.name, b.name)
        assertNotEquals(a, b)
    }

    @Test
    fun reExportingADocumentReusesItsTarget() {
        assertEquals(ExportFiles.exportTargetFor(tmp.root, "Notes.pdf"), ExportFiles.exportTargetFor(tmp.root, "Notes.pdf"))
    }

    @Test
    fun pruneCleansPerDocumentFolders() {
        val now = System.currentTimeMillis()
        val old = now - TimeUnit.HOURS.toMillis(25)

        fun folder(name: String) = File(tmp.root, name).apply { mkdirs() }
        fun File.child(name: String, modified: Long) =
            File(this, name).apply { writeText("x"); setLastModified(modified) }

        // Only an old export inside, folder untouched for a day: both go.
        val stale = folder("stale")
        val staleExport = stale.child("Old (annotated).pdf", old)
        stale.setLastModified(old)
        // A recent export: stays with its folder.
        val fresh = folder("fresh")
        val freshExport = fresh.child("New (annotated).pdf", now - 1_000)
        // Old export in a folder that was touched recently: the file goes, the folder stays for now.
        val busy = folder("busy")
        val busyExport = busy.child("Busy (annotated).pdf", old)
        busy.setLastModified(now - 1_000)
        // The folder being exported into stays even when it's empty and old; stale leftovers in it go.
        val keepFolder = folder("keep")
        val leftover = keepFolder.child("Keep (annotated).pdf.part", old)
        keepFolder.setLastModified(old)
        val keep = File(keepFolder, "Keep (annotated).pdf")

        ExportFiles.pruneExports(tmp.root, keep = keep, now = now)

        assertFalse(staleExport.exists())
        assertFalse(stale.exists())
        assertTrue(freshExport.exists())
        assertFalse(busyExport.exists())
        assertTrue(busy.isDirectory)
        assertFalse(leftover.exists())
        assertTrue(keepFolder.isDirectory)
    }

    @Test
    fun pruneKeepsTheTargetInsideItsFolder() {
        val now = System.currentTimeMillis()
        val old = now - TimeUnit.HOURS.toMillis(25)
        val target = ExportFiles.exportTargetFor(tmp.root, "Same.pdf")
        target.parentFile!!.mkdirs()
        target.writeText("x")
        target.setLastModified(old)
        target.parentFile!!.setLastModified(old)

        ExportFiles.pruneExports(tmp.root, keep = target, now = now)

        assertTrue(target.exists())
    }

    @Test
    fun rasterScaleIsTwoForNormalPagesAndCappedForHugeOnes() {
        assertEquals(2f, rasterScale(612f, 792f), 0f)
        assertEquals(2f, rasterScale(842f, 1191f), 0f) // A3
        val a0 = rasterScale(2384f, 3370f)
        assertTrue(a0 < 2f)
        val pixels = (2384f * a0) * (3370f * a0)
        assertEquals(MAX_RASTER_PIXELS, pixels, MAX_RASTER_PIXELS * 1e-3f)
        assertEquals(2f, rasterScale(0f, 0f), 0f)
    }
}
