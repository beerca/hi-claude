package app.lightsout

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

/** All app data, kept locally in SharedPreferences. */
object Store {
    private const val KEY_ACTIVE = "active_session"
    private const val KEY_NIGHTS = "nights"
    private const val KEY_NAME = "name"
    private const val KEY_VIDEO_COUNTS = "video_counts"
    private const val KEY_VIDEO_PACKAGES = "video_packages"
    private const val KEY_GLANCE_SECONDS = "glance_seconds"
    private const val KEY_MAX_NIGHT_HOURS = "max_night_hours"
    private const val KEY_LAST_WAKE_MINUTES = "last_manual_wake_minutes"
    private const val KEY_NOTIFICATIONS_SKIPPED = "notifications_skipped"
    private const val KEY_USAGE_SKIPPED = "usage_access_skipped"
    private const val KEY_BATTERY_SKIPPED = "battery_skipped"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences("lightsout", Context.MODE_PRIVATE)

    fun activeSession(context: Context): ActiveSession? =
        prefs(context).getString(KEY_ACTIVE, null)?.let { activeSessionFromJson(JSONObject(it)) }

    // commit() rather than apply(): callers include receivers and workers that may die right after.
    fun setActiveSession(context: Context, session: ActiveSession?) = prefs(context).edit(commit = true) {
        if (session == null) remove(KEY_ACTIVE) else putString(KEY_ACTIVE, session.toJson().toString())
    }

    /** Newest first. */
    fun nights(context: Context): List<NightRecord> {
        val array = JSONArray(prefs(context).getString(KEY_NIGHTS, "[]"))
        return (0 until array.length()).map { nightRecordFromJson(array.getJSONObject(it)) }
            .sortedByDescending { it.session.startMs }
    }

    fun addNight(context: Context, night: NightRecord) {
        val array = JSONArray(prefs(context).getString(KEY_NIGHTS, "[]")).put(night.toJson())
        prefs(context).edit(commit = true) { putString(KEY_NIGHTS, array.toString()) }
    }

    /** Removes the entry that started at [startMs]. */
    fun deleteNight(context: Context, startMs: Long) {
        val array = JSONArray(prefs(context).getString(KEY_NIGHTS, "[]"))
        val kept = JSONArray()
        for (i in 0 until array.length()) {
            val night = array.getJSONObject(i)
            if (night.getJSONObject("session").getLong("startMs") != startMs) kept.put(night)
        }
        prefs(context).edit(commit = true) { putString(KEY_NIGHTS, kept.toString()) }
    }

    fun settings(context: Context): Settings {
        val p = prefs(context)
        return Settings(
            name = p.getString(KEY_NAME, "") ?: "",
            defaultVideoCounts = p.getBoolean(KEY_VIDEO_COUNTS, false),
            videoPackages = p.getStringSet(KEY_VIDEO_PACKAGES, null)?.toSet() ?: Settings.DEFAULT_VIDEO_PACKAGES,
            glanceSeconds = p.getInt(KEY_GLANCE_SECONDS, 30),
            maxNightHours = p.getInt(KEY_MAX_NIGHT_HOURS, 12),
        )
    }

    fun saveSettings(context: Context, settings: Settings) = prefs(context).edit {
        putString(KEY_NAME, settings.name)
        putBoolean(KEY_VIDEO_COUNTS, settings.defaultVideoCounts)
        putStringSet(KEY_VIDEO_PACKAGES, settings.videoPackages)
        putInt(KEY_GLANCE_SECONDS, settings.glanceSeconds)
        putInt(KEY_MAX_NIGHT_HOURS, settings.maxNightHours)
    }

    /** Last manually entered wake-up time, as minutes after midnight. */
    fun lastManualWakeMinutes(context: Context): Int = prefs(context).getInt(KEY_LAST_WAKE_MINUTES, 7 * 60)

    fun setLastManualWakeMinutes(context: Context, minutes: Int) =
        prefs(context).edit { putInt(KEY_LAST_WAKE_MINUTES, minutes) }

    fun notificationsSkipped(context: Context): Boolean = prefs(context).getBoolean(KEY_NOTIFICATIONS_SKIPPED, false)

    fun skipNotifications(context: Context) = prefs(context).edit { putBoolean(KEY_NOTIFICATIONS_SKIPPED, true) }

    /** The user chose live tracking instead of granting Usage access. */
    fun usageAccessSkipped(context: Context): Boolean = prefs(context).getBoolean(KEY_USAGE_SKIPPED, false)

    fun skipUsageAccess(context: Context) = prefs(context).edit { putBoolean(KEY_USAGE_SKIPPED, true) }

    fun batterySkipped(context: Context): Boolean = prefs(context).getBoolean(KEY_BATTERY_SKIPPED, false)

    fun skipBattery(context: Context) = prefs(context).edit { putBoolean(KEY_BATTERY_SKIPPED, true) }
}
