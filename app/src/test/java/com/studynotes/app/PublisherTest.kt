package com.studynotes.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PublisherTest {
    @Test
    fun copyright_firstYearIsASingleYear() {
        assertEquals("© 2026 WireByte Technologies", Publisher.copyright(currentYear = 2026))
    }

    @Test
    fun copyright_laterYearsBecomeARange() {
        assertEquals("© 2026–2028 WireByte Technologies", Publisher.copyright(currentYear = 2028))
    }

    @Test
    fun copyright_neverRunsBackwardsOnAWrongClock() {
        assertEquals("© 2026 WireByte Technologies", Publisher.copyright(currentYear = 2020))
    }
}
