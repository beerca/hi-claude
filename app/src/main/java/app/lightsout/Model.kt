package app.lightsout

import app.lightsout.core.Segment
import app.lightsout.core.SegmentKind
import org.json.JSONArray
import org.json.JSONObject

enum class WakeSource { ALARM, MANUAL }

enum class EndReason { PLANNED, ALARM_DISMISSED, WOKE_UP }

/**
 * A night in progress. Settings that affect the score are copied in at start, so changing them
 * in the morning can't rewrite the night.
 */
data class ActiveSession(
    val startMs: Long,
    val endMs: Long,
    val wakeSource: WakeSource,
    val alarmPackage: String?,
    val videoCounts: Boolean,
    val videoPackages: Set<String>,
    val glanceThresholdMs: Long,
    val maxNightMs: Long,
    val endReason: EndReason = EndReason.PLANNED,
) {
    /** Time before this isn't counted, so tapping "start" hours early doesn't pay off. */
    val countedStartMs: Long get() = maxOf(startMs, endMs - maxNightMs)
}

data class NightRecord(
    val session: ActiveSession,
    val screenUseMs: Long,
    val videoMs: Long,
    val glanceMs: Long,
    val segments: List<Segment>,
) {
    val windowMs: Long get() = (session.endMs - session.countedStartMs).coerceAtLeast(0)
    val attemptedMs: Long
        get() = (windowMs - screenUseMs - if (session.videoCounts) 0 else videoMs).coerceAtLeast(0)
}

data class Settings(
    val name: String,
    val defaultVideoCounts: Boolean,
    val videoPackages: Set<String>,
    val glanceSeconds: Int,
    val maxNightHours: Int,
) {
    val maxNightMs: Long get() = maxNightHours * 3_600_000L

    companion object {
        val DEFAULT_VIDEO_PACKAGES = setOf(
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "com.amazon.avod.thirdpartyclient",
            "com.disney.disneyplus",
            "org.videolan.vlc",
            "tv.twitch.android.app",
            "com.plexapp.android",
        )
    }
}

internal fun ActiveSession.toJson(): JSONObject = JSONObject()
    .put("startMs", startMs)
    .put("endMs", endMs)
    .put("wakeSource", wakeSource.name)
    .put("alarmPackage", alarmPackage ?: JSONObject.NULL)
    .put("videoCounts", videoCounts)
    .put("videoPackages", JSONArray(videoPackages.toList()))
    .put("glanceThresholdMs", glanceThresholdMs)
    .put("maxNightMs", maxNightMs)
    .put("endReason", endReason.name)

internal fun activeSessionFromJson(o: JSONObject) = ActiveSession(
    startMs = o.getLong("startMs"),
    endMs = o.getLong("endMs"),
    wakeSource = WakeSource.valueOf(o.getString("wakeSource")),
    alarmPackage = o.optStringOrNull("alarmPackage"),
    videoCounts = o.getBoolean("videoCounts"),
    videoPackages = o.getJSONArray("videoPackages").strings().toSet(),
    glanceThresholdMs = o.getLong("glanceThresholdMs"),
    maxNightMs = o.getLong("maxNightMs"),
    endReason = EndReason.valueOf(o.getString("endReason")),
)

internal fun NightRecord.toJson(): JSONObject = JSONObject()
    .put("session", session.toJson())
    .put("screenUseMs", screenUseMs)
    .put("videoMs", videoMs)
    .put("glanceMs", glanceMs)
    .put("segments", JSONArray().apply {
        segments.forEach {
            put(
                JSONObject()
                    .put("s", it.startMs)
                    .put("e", it.endMs)
                    .put("k", it.kind.name)
                    .put("p", it.packageName ?: JSONObject.NULL)
            )
        }
    })

internal fun nightRecordFromJson(o: JSONObject): NightRecord {
    val segments = o.getJSONArray("segments")
    return NightRecord(
        session = activeSessionFromJson(o.getJSONObject("session")),
        screenUseMs = o.getLong("screenUseMs"),
        videoMs = o.getLong("videoMs"),
        glanceMs = o.getLong("glanceMs"),
        segments = (0 until segments.length()).map { i ->
            val s = segments.getJSONObject(i)
            Segment(s.getLong("s"), s.getLong("e"), SegmentKind.valueOf(s.getString("k")), s.optStringOrNull("p"))
        },
    )
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }
