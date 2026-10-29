package app.lightsout

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class NightsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val hour = 3_600_000L

    private fun at(d: Int, h: Int, m: Int = 0) =
        ZonedDateTime.of(2026, 10, d, h, m, 0, 0, zone).toInstant().toEpochMilli()

    /** An entry from [startMs] lasting [hours] with [screenMinutes] of screen use. */
    private fun entry(startMs: Long, hours: Long, screenMinutes: Long = 0) = NightRecord(
        session = ActiveSession(
            startMs = startMs,
            endMs = startMs + hours * hour,
            wakeSource = WakeSource.ALARM,
            alarmPackage = null,
            videoCounts = false,
            videoPackages = emptySet(),
            glanceThresholdMs = 30_000,
            maxNightMs = 12 * hour,
        ),
        screenUseMs = screenMinutes * 60_000,
        videoMs = 0,
        glanceMs = 0,
        segments = emptyList(),
    )

    @Test
    fun interruptedNightIsOneNight() {
        val nights = Nights.group(
            listOf(entry(at(27, 23), 3), entry(at(28, 2, 30), 4), entry(at(28, 6, 15), 1)),
            zone,
        )
        assertEquals(1, nights.size)
        assertEquals(LocalDate.of(2026, 10, 27), nights.single().date)
        assertEquals(8 * hour, nights.single().attemptedMs)
    }

    @Test
    fun nightsSplitAtNoon() {
        val nights = Nights.group(listOf(entry(at(28, 11), 1), entry(at(28, 13), 1)), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 27)), nights.map { it.date })
    }

    @Test
    fun averageIsPerNightNotPerEntry() {
        val nights = Nights.group(
            listOf(
                entry(at(26, 23), 8),
                entry(at(27, 23), 3), entry(at(28, 2), 3, screenMinutes = 60), // 5 h
            ),
            zone,
        )
        assertEquals(2, nights.size)
        assertEquals(13 * hour / 2, Nights.averageMs(nights))
    }

    @Test
    fun entriesAreOldestFirstAndNightsNewestFirst() {
        val nights = Nights.group(listOf(entry(at(28, 2), 1), entry(at(26, 23), 1), entry(at(27, 23), 1)), zone)
        assertEquals(listOf(LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 26)), nights.map { it.date })
        assertEquals(listOf(at(27, 23), at(28, 2)), nights.first().entries.map { it.session.startMs })
    }
}
