package app.lightsout

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings as AndroidSettings
import android.service.quicksettings.TileService
import app.lightsout.core.EventType
import app.lightsout.core.RawEvent

data class NextAlarm(val triggerMs: Long, val packageName: String?)

object Alarms {
    /** The next alarm any app scheduled with setAlarmClock(), e.g. the Clock app. */
    fun next(context: Context): NextAlarm? {
        val info = context.getSystemService(AlarmManager::class.java).nextAlarmClock ?: return null
        return NextAlarm(info.triggerTime, info.showIntent?.creatorPackage)
    }

    /**
     * The next alarm if it plausibly ends tonight's sleep. An alarm further away than the max
     * night length (e.g. Friday night, next alarm Monday) is treated as no alarm.
     */
    fun nextUsable(context: Context, nowMs: Long, maxNightMs: Long): NextAlarm? =
        next(context)?.takeIf { it.triggerMs > nowMs && it.triggerMs - nowMs <= maxNightMs }
}

object UsageAccess {
    fun granted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        if (mode == AppOpsManager.MODE_DEFAULT) {
            return context.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun openSettings(context: Context) {
        val direct = Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
        try {
            context.startActivity(direct)
        } catch (e: Exception) {
            context.startActivity(Intent(AndroidSettings.ACTION_USAGE_ACCESS_SETTINGS))
        }
    }

    fun events(context: Context, fromMs: Long, toMs: Long): List<RawEvent> {
        val usage = context.getSystemService(UsageStatsManager::class.java)
        val events = usage.queryEvents(fromMs, toMs) ?: return emptyList()
        val event = UsageEvents.Event()
        val out = ArrayList<RawEvent>()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType in EventType.RELEVANT) {
                out += RawEvent(event.timeStamp, event.eventType, event.packageName)
            }
        }
        return out
    }
}

object Apps {
    fun label(context: Context, packageName: String?): String? {
        packageName ?: return null
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}

object Tile {
    fun refresh(context: Context) {
        try {
            TileService.requestListeningState(context, ComponentName(context, SleepTileService::class.java))
        } catch (e: Exception) {
            // Tile not added, or the system refused; the tile refreshes itself when shown anyway.
        }
    }
}
