package app.lightsout

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import app.lightsout.core.EventType
import app.lightsout.core.Gaps
import app.lightsout.core.SegmentKind
import app.lightsout.core.UsageAnalyzer
import java.util.concurrent.TimeUnit

/** Starting, adjusting and finishing nights. Synchronized: the UI, tile, receivers, service and worker all call in. */
object Sessions {
    private const val FINALIZE_WORK = "finalize-night"
    private const val MINUTE_MS = 60_000L

    /** History replayed before the window so the analyzer knows whether the screen was on. */
    private const val LOOKBACK_MS = 3 * 3_600_000L

    sealed interface StartResult {
        /** [trackingStarted] is false if live tracking couldn't start from here (e.g. from the tile). */
        data class Started(val session: ActiveSession, val trackingStarted: Boolean) : StartResult
        data object NeedsWakeTime : StartResult
    }

    /** Starts a night ending at the next alarm, or asks for a wake-up time if there is none. */
    @Synchronized
    fun startWithAlarm(context: Context, videoCounts: Boolean, nowMs: Long): StartResult {
        finalizeIfDue(context, nowMs)
        val settings = Store.settings(context)
        val alarm = Alarms.nextUsable(context, nowMs, settings.maxNightMs) ?: return StartResult.NeedsWakeTime
        return start(context, nowMs, alarm.triggerMs, WakeSource.ALARM, alarm.packageName, videoCounts, settings)
    }

    @Synchronized
    fun startWithWakeTime(context: Context, wakeMs: Long, videoCounts: Boolean, nowMs: Long): StartResult.Started {
        finalizeIfDue(context, nowMs)
        return start(context, nowMs, wakeMs, WakeSource.MANUAL, null, videoCounts, Store.settings(context))
    }

    private fun start(
        context: Context,
        nowMs: Long,
        endMs: Long,
        source: WakeSource,
        alarmPackage: String?,
        videoCounts: Boolean,
        settings: Settings,
    ): StartResult.Started {
        val session = ActiveSession(
            startMs = nowMs,
            endMs = endMs,
            wakeSource = source,
            alarmPackage = alarmPackage,
            videoCounts = videoCounts,
            videoPackages = settings.videoPackages,
            glanceThresholdMs = settings.glanceSeconds * 1_000L,
            maxNightMs = settings.maxNightMs,
            mode = if (UsageAccess.granted(context)) TrackingMode.USAGE else TrackingMode.LIVE,
        )
        Store.setActiveSession(context, session)
        scheduleFinalize(context, session.endMs, nowMs)
        Tile.refresh(context)
        val trackingStarted = when (session.mode) {
            TrackingMode.USAGE -> true
            TrackingMode.LIVE -> {
                LiveLog.clear(context)
                LiveTracking.start(context)
            }
        }
        return StartResult.Started(session, trackingStarted)
    }

    @Synchronized
    fun cancel(context: Context) {
        Store.setActiveSession(context, null)
        WorkManager.getInstance(context).cancelUniqueWork(FINALIZE_WORK)
        LiveTracking.stop(context)
        LiveLog.clear(context)
        Tile.refresh(context)
    }

    /** "I'm up": ends the night now (never later than planned) and computes it. */
    @Synchronized
    fun wakeUp(context: Context, nowMs: Long): NightRecord? {
        val session = Store.activeSession(context) ?: return null
        if (nowMs < session.endMs) {
            Store.setActiveSession(
                context,
                session.copy(endMs = maxOf(nowMs, session.startMs), endReason = EndReason.WOKE_UP),
            )
        }
        return finalizeIfDue(context, nowMs)
    }

    /**
     * The next alarm changed during the night. The first alarm of the morning ends the night:
     * an earlier alarm moves the end forward, a later one (alarm moved) pushes it back, and
     * removing the alarm (e.g. "dismiss upcoming alarm") ends the night now.
     */
    @Synchronized
    fun onNextAlarmChanged(context: Context, nowMs: Long) {
        val session = Store.activeSession(context) ?: return
        // Firing, snoozing or dismissing the morning alarm also changes the next alarm.
        if (nowMs >= session.endMs - MINUTE_MS) return
        val next = Alarms.next(context)?.takeIf { it.triggerMs > nowMs }
        val updated = when {
            next != null && next.triggerMs < session.endMs ->
                session.copy(endMs = next.triggerMs, wakeSource = WakeSource.ALARM, alarmPackage = next.packageName)
            session.wakeSource != WakeSource.ALARM -> return
            next != null && next.triggerMs == session.endMs -> return
            next != null && next.triggerMs - session.startMs <= session.maxNightMs ->
                session.copy(endMs = next.triggerMs, alarmPackage = next.packageName)
            else -> session.copy(endMs = nowMs, endReason = EndReason.ALARM_DISMISSED)
        }
        Store.setActiveSession(context, updated)
        scheduleFinalize(context, updated.endMs, nowMs)
        if (updated.mode == TrackingMode.LIVE) LiveTracking.updateNotification(context, updated)
        Tile.refresh(context)
    }

    /**
     * Computes and stores the night once its end has passed. Returns null if there is nothing to
     * finalize yet, or a usage-tracked night lacks usage access (it then stays pending).
     */
    @Synchronized
    fun finalizeIfDue(context: Context, nowMs: Long): NightRecord? {
        val session = Store.activeSession(context) ?: return null
        if (nowMs < session.endMs) return null
        val from = session.countedStartMs
        val events = when (session.mode) {
            TrackingMode.USAGE -> {
                if (!UsageAccess.granted(context)) return null
                UsageAccess.events(context, from - LOOKBACK_MS, session.endMs)
            }
            TrackingMode.LIVE -> LiveLog.read(context)
        }
        val unmonitoredMs = when (session.mode) {
            TrackingMode.USAGE -> 0L
            TrackingMode.LIVE -> Gaps.unmonitoredMs(
                windowStartMs = from,
                windowEndMs = session.endMs,
                sessionStartMs = session.startMs,
                recordedMs = events.map { it.timeMs },
                serviceStartsMs = events.filter { it.type == EventType.SERVICE_STARTED }.map { it.timeMs },
                deathsMs = LiveTracking.processDeaths(context, session.startMs, session.endMs),
            )
        }
        val analysis = UsageAnalyzer.analyze(
            events, from, session.endMs, session.videoPackages, session.glanceThresholdMs,
        )
        val night = NightRecord(
            session = session,
            screenUseMs = analysis.totalMs(SegmentKind.SCREEN_USE),
            videoMs = analysis.totalMs(SegmentKind.VIDEO),
            glanceMs = analysis.totalMs(SegmentKind.GLANCE),
            segments = analysis.segments,
            unmonitoredMs = unmonitoredMs,
        )
        Store.addNight(context, night)
        Store.setActiveSession(context, null)
        if (session.mode == TrackingMode.LIVE) {
            LiveTracking.stop(context)
            LiveLog.clear(context)
        }
        Tile.refresh(context)
        return night
    }

    /** Finalizes in the background as soon as possible (the night's end has passed). */
    fun finalizeSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<FinalizeWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(FINALIZE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    private fun scheduleFinalize(context: Context, endMs: Long, nowMs: Long) {
        val request = OneTimeWorkRequestBuilder<FinalizeWorker>()
            .setInitialDelay((endMs - nowMs).coerceAtLeast(0) + 2 * MINUTE_MS, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FINALIZE_WORK, ExistingWorkPolicy.REPLACE, request)
    }
}
