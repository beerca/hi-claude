package app.lightsout

import android.content.Context
import app.lightsout.core.SegmentKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

object Format {
    private val clockFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)

    fun duration(ms: Long): String {
        val minutes = (ms + 30_000) / 60_000
        return if (minutes >= 60) "%dh %02dm".format(minutes / 60, minutes % 60) else "${minutes}m"
    }

    fun clock(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(clockFormat)

    fun nightLabel(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

    fun wake(context: Context, session: ActiveSession): String = when (session.endReason) {
        EndReason.WOKE_UP -> "got up early"
        EndReason.ALARM_DISMISSED -> "alarm dismissed early"
        EndReason.PLANNED -> when (session.wakeSource) {
            WakeSource.ALARM -> Apps.label(context, session.alarmPackage)?.let { "alarm, $it" } ?: "alarm"
            WakeSource.MANUAL -> "entered wake-up time"
        }
    }

    /** "23:18 → 07:00 (alarm, Clock)" */
    fun entrySpan(context: Context, entry: NightRecord): String =
        "${clock(entry.session.startMs)} → ${clock(entry.session.endMs)} (${wake(context, entry.session)})"

    fun breakdown(entry: NightRecord): String {
        val video = if (entry.session.videoCounts) "counted" else "subtracted"
        return "Screen use −${duration(entry.screenUseMs)} · video ${duration(entry.videoMs)} $video"
    }

    fun breakdown(night: Night): String {
        val video = listOfNotNull(
            night.countedVideoMs.takeIf { it > 0 }?.let { "${duration(it)} counted" },
            night.subtractedVideoMs.takeIf { it > 0 }?.let { "${duration(it)} subtracted" },
        ).ifEmpty { listOf("0m") }.joinToString(", ")
        return "Screen use −${duration(night.screenUseMs)} · video $video"
    }

    fun tracking(entry: NightRecord): String? = tracking(entry.session.mode == TrackingMode.LIVE, entry.unmonitoredMs)

    fun tracking(night: Night): String? = tracking(night.live, night.unmonitoredMs)

    private fun tracking(live: Boolean, unmonitoredMs: Long): String? = when {
        !live -> null
        unmonitoredMs > 0 -> "Live tracking · ⚠ ${duration(unmonitoredMs)} unmonitored (app was stopped)"
        else -> "Live tracking"
    }

    fun segmentKind(kind: SegmentKind): String = when (kind) {
        SegmentKind.SCREEN_USE -> "Screen use"
        SegmentKind.VIDEO -> "Video"
        SegmentKind.GLANCE -> "Glance (ignored)"
    }

    /** [recent] is newest first and includes [night]. */
    fun shareText(context: Context, name: String, night: Night, recent: List<Night>): String {
        val who = if (name.isBlank()) "" else "${name.trim()} · "
        val spans = night.entries.joinToString("\n") { entry ->
            val clipped = if (entry.session.countedStartMs > entry.session.startMs) {
                " · counted from ${clock(entry.session.countedStartMs)}"
            } else ""
            entrySpan(context, entry) + clipped
        }
        val lastWeek = recent.take(7)
        return "🌙 ${who}night of ${nightLabel(night.date)}\n" +
            "Attempted sleep: ${duration(night.attemptedMs)}\n" +
            spans + "\n" +
            breakdown(night) +
            (tracking(night)?.let { "\n$it" } ?: "") +
            "\n${lastWeek.size}-night average: ${duration(Nights.averageMs(lastWeek))}"
    }
}
