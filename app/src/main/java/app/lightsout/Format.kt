package app.lightsout

import android.content.Context
import app.lightsout.core.SegmentKind
import java.time.Instant
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

    /** A night started after midnight still belongs to the previous evening. */
    fun nightLabel(startMs: Long): String = Instant.ofEpochMilli(startMs - 6 * 3_600_000L)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))

    fun wake(context: Context, session: ActiveSession): String = when (session.endReason) {
        EndReason.WOKE_UP -> "got up early"
        EndReason.ALARM_DISMISSED -> "alarm dismissed early"
        EndReason.PLANNED -> when (session.wakeSource) {
            WakeSource.ALARM -> Apps.label(context, session.alarmPackage)?.let { "alarm, $it" } ?: "alarm"
            WakeSource.MANUAL -> "entered wake-up time"
        }
    }

    fun breakdown(night: NightRecord): String {
        val video = if (night.session.videoCounts) "counted" else "subtracted"
        return "Screen use −${duration(night.screenUseMs)} · video ${duration(night.videoMs)} $video"
    }

    fun tracking(night: NightRecord): String? = when {
        night.session.mode == TrackingMode.USAGE -> null
        night.unmonitoredMs > 0 -> "Live tracking · ⚠ ${duration(night.unmonitoredMs)} unmonitored (app was stopped)"
        else -> "Live tracking"
    }

    fun segmentKind(kind: SegmentKind): String = when (kind) {
        SegmentKind.SCREEN_USE -> "Screen use"
        SegmentKind.VIDEO -> "Video"
        SegmentKind.GLANCE -> "Glance (ignored)"
    }

    /** [recent] is newest first and includes [night]. */
    fun shareText(context: Context, name: String, night: NightRecord, recent: List<NightRecord>): String {
        val who = if (name.isBlank()) "" else "${name.trim()} · "
        val clipped = if (night.session.countedStartMs > night.session.startMs) {
            "\nCounted from ${clock(night.session.countedStartMs)} (max night length)"
        } else ""
        val average = recent.take(7).let { nights ->
            "\n${nights.size}-night average: ${duration(nights.sumOf { it.attemptedMs } / nights.size)}"
        }
        return "🌙 ${who}night of ${nightLabel(night.session.startMs)}\n" +
            "Attempted sleep: ${duration(night.attemptedMs)}\n" +
            "${clock(night.session.startMs)} → ${clock(night.session.endMs)} (${wake(context, night.session)})" +
            clipped + "\n" +
            breakdown(night) +
            (tracking(night)?.let { "\n$it" } ?: "") +
            average
    }
}
