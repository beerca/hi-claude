package app.lightsout

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * All entries of one night, e.g. a night interrupted with "I'm up" and started again. The
 * challenge compares nights, so totals and averages are per night, not per entry.
 */
data class Night(
    /** The evening the night belongs to. */
    val date: LocalDate,
    /** Oldest first. */
    val entries: List<NightRecord>,
) {
    val attemptedMs: Long get() = entries.sumOf { it.attemptedMs }
    val screenUseMs: Long get() = entries.sumOf { it.screenUseMs }
    val countedVideoMs: Long get() = entries.filter { it.session.videoCounts }.sumOf { it.videoMs }
    val subtractedVideoMs: Long get() = entries.filterNot { it.session.videoCounts }.sumOf { it.videoMs }
    val unmonitoredMs: Long get() = entries.sumOf { it.unmonitoredMs }
    val live: Boolean get() = entries.any { it.session.mode == TrackingMode.LIVE }
}

object Nights {
    /** Nights run from noon to noon, so going back to bed at 6:00 is still the same night. */
    private const val DAY_START_OFFSET_MS = 12 * 3_600_000L

    fun dateOf(startMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(startMs - DAY_START_OFFSET_MS).atZone(zone).toLocalDate()

    /** Newest night first. */
    fun group(records: List<NightRecord>, zone: ZoneId = ZoneId.systemDefault()): List<Night> =
        records.groupBy { dateOf(it.session.startMs, zone) }
            .map { (date, entries) -> Night(date, entries.sortedBy { it.session.startMs }) }
            .sortedByDescending { it.date }

    /** Average attempted sleep per night over [nights]; 0 if empty. */
    fun averageMs(nights: List<Night>): Long =
        if (nights.isEmpty()) 0 else nights.sumOf { it.attemptedMs } / nights.size
}
