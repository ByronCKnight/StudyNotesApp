package com.studynotes.app.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OpenPageSlotTest {

    private val events = ArrayList<String>()

    private inner class FakePage(val index: Int) : AutoCloseable {
        override fun close() {
            events += "close $index"
        }
    }

    private fun slot(failOn: Int? = null) = OpenPageSlot { index ->
        if (index == failOn) throw IllegalStateException("cannot open $index")
        events += "open $index"
        FakePage(index)
    }

    @Test
    fun repeatedAccessReusesTheOpenPage() {
        val slot = slot()
        val first = slot[3]
        assertSame(first, slot[3])
        assertSame(first, slot[3])
        assertEquals(listOf("open 3"), events)
    }

    @Test
    fun switchingPagesClosesTheOldOneBeforeOpeningTheNext() {
        val slot = slot()
        slot[0]
        slot[1]
        slot[0]
        assertEquals(listOf("open 0", "close 0", "open 1", "close 1", "open 0"), events)
    }

    @Test
    fun clearClosesOnceAndAllowsReopening() {
        val slot = slot()
        slot[2]
        slot.clear()
        slot.clear()
        assertEquals(listOf("open 2", "close 2"), events)
        assertEquals(2, slot[2].index)
        assertEquals(listOf("open 2", "close 2", "open 2"), events)
    }

    @Test
    fun failedOpenLeavesTheSlotEmpty() {
        val slot = slot(failOn = 5)
        slot[4]
        try {
            slot[5]
            fail("expected the open to fail")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("5"))
        }
        // Page 4 was closed before the failed attempt and is reopened on demand.
        slot[4]
        slot.clear()
        assertEquals(listOf("open 4", "close 4", "open 4", "close 4"), events)
    }
}
