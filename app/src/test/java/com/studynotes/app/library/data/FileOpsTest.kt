package com.studynotes.app.library.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileOpsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun moveRenamesAndRefusesToOverwrite() {
        val src = tmp.newFile("a.pdf").apply { writeText("A") }
        val dst = File(tmp.root, "b.pdf")
        assertTrue(FileOps.move(src, dst))
        assertFalse(src.exists())
        assertEquals("A", dst.readText())

        val other = tmp.newFile("c.pdf").apply { writeText("C") }
        assertFalse(FileOps.move(other, dst))
        assertEquals("C", other.readText())
        assertEquals("A", dst.readText())
    }

    @Test
    fun copyKeepsBytesAndModificationTimeAndLeavesNoTempFile() {
        val bytes = ByteArray(200_000) { (it * 7).toByte() }
        val src = tmp.newFile("src.pdf").apply {
            writeBytes(bytes)
            setLastModified(1_650_000_000_000L)
        }
        val dst = File(tmp.root, "nested/dir/dst.pdf")

        assertTrue(FileOps.copy(src, dst))

        assertArrayEquals(bytes, dst.readBytes())
        assertEquals(1_650_000_000_000L, dst.lastModified())
        assertTrue(src.exists())
        assertEquals(listOf("dst.pdf"), dst.parentFile!!.list()!!.toList())
    }

    @Test
    fun copyRefusesMissingSourceOrExistingTarget() {
        val dst = tmp.newFile("dst.pdf").apply { writeText("keep") }
        assertFalse(FileOps.copy(File(tmp.root, "missing.pdf"), File(tmp.root, "x.pdf")))
        assertFalse(FileOps.copy(tmp.newFile("src.pdf"), dst))
        assertEquals("keep", dst.readText())
    }

    @Test
    fun sameContentComparesBytes() {
        val big = ByteArray(150_000) { (it % 13).toByte() }
        val a = tmp.newFile("a").apply { writeBytes(big) }
        val b = tmp.newFile("b").apply { writeBytes(big) }
        val lastByteDiffers = tmp.newFile("c").apply { writeBytes(big.copyOf().also { it[it.size - 1] = 99 }) }
        val shorter = tmp.newFile("d").apply { writeBytes(big.copyOf(big.size - 1)) }

        assertTrue(FileOps.sameContent(a, b))
        assertFalse(FileOps.sameContent(a, lastByteDiffers))
        assertFalse(FileOps.sameContent(a, shorter))
        assertFalse(FileOps.sameContent(a, File(tmp.root, "missing")))
        assertTrue(FileOps.sameContent(tmp.newFile("empty1"), tmp.newFile("empty2")))
    }
}
