package app.lightsout.core

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object Times {
    /** The next moment after [nowMs] at which the wall clock shows [hour]:[minute]. */
    fun nextOccurrence(hour: Int, minute: Int, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        var candidate = now.with(LocalTime.of(hour, minute))
        if (!candidate.isAfter(now)) candidate = candidate.plusDays(1)
        return candidate.toInstant().toEpochMilli()
    }
}
