package app.lightsout.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class TimesTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, 0, 0, berlin).toInstant().toEpochMilli()

    @Test
    fun wakeTimeAfterMidnightIsTomorrow() {
        assertEquals(at(2026, 10, 29, 7, 0), Times.nextOccurrence(7, 0, at(2026, 10, 28, 23, 15), berlin))
    }

    @Test
    fun wakeTimeLaterTodayIsToday() {
        assertEquals(at(2026, 10, 29, 7, 30), Times.nextOccurrence(7, 30, at(2026, 10, 29, 1, 0), berlin))
    }

    @Test
    fun sameMinuteMeansTomorrow() {
        assertEquals(at(2026, 10, 29, 7, 0), Times.nextOccurrence(7, 0, at(2026, 10, 28, 7, 0), berlin))
    }

    @Test
    fun dstChangeKeepsWallClockTime() {
        // Clocks go back on 2026-10-25 in Berlin; the night is 9 hours long, not 8.
        val bedtime = at(2026, 10, 24, 23, 0)
        val wake = Times.nextOccurrence(7, 0, bedtime, berlin)
        assertEquals(at(2026, 10, 25, 7, 0), wake)
        assertEquals(9 * 3_600_000L, wake - bedtime)
    }
}
