package app.lightsout.core

import app.lightsout.core.EventType.ACTIVITY_PAUSED
import app.lightsout.core.EventType.ACTIVITY_RESUMED
import app.lightsout.core.EventType.DEVICE_SHUTDOWN
import app.lightsout.core.EventType.KEYGUARD_HIDDEN
import app.lightsout.core.EventType.KEYGUARD_SHOWN
import app.lightsout.core.EventType.SCREEN_INTERACTIVE
import app.lightsout.core.EventType.SCREEN_NON_INTERACTIVE
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageAnalyzerTest {
    private val sec = 1_000L
    private val min = 60 * sec
    private val start = 1_000_000 * sec
    private val end = start + 8 * 60 * min
    private val youtube = "com.google.android.youtube"
    private val insta = "com.instagram.android"

    private fun analyze(vararg events: RawEvent, glance: Long = 30 * sec) =
        UsageAnalyzer.analyze(events.toList(), start, end, setOf(youtube), glance)

    private fun ev(at: Long, type: Int, pkg: String? = null) = RawEvent(at, type, pkg)

    /** Screen goes dark and locks `afterMs` into the window. */
    private fun lockAt(at: Long) = arrayOf(ev(at, SCREEN_NON_INTERACTIVE), ev(at, KEYGUARD_SHOWN))

    @Test
    fun screenOffBeforeStartMeansNoUse() {
        val a = analyze(*lockAt(start - 5 * min))
        assertEquals(emptyList<Segment>(), a.segments)
    }

    @Test
    fun noHistoryAssumesScreenOnUntilFirstScreenOff() {
        val a = analyze(*lockAt(start + 10 * sec))
        assertEquals(10 * sec, a.totalMs(SegmentKind.SCREEN_USE))
    }

    @Test
    fun shortLockScreenWakeIsAGlance() {
        val a = analyze(
            *lockAt(start - min),
            ev(start + 60 * min, SCREEN_INTERACTIVE),
            ev(start + 60 * min + 10 * sec, SCREEN_NON_INTERACTIVE),
        )
        assertEquals(0L, a.totalMs(SegmentKind.SCREEN_USE))
        assertEquals(10 * sec, a.totalMs(SegmentKind.GLANCE))
    }

    @Test
    fun longLockScreenWakeIsScreenUse() {
        val a = analyze(
            *lockAt(start - min),
            ev(start + 60 * min, SCREEN_INTERACTIVE),
            ev(start + 62 * min, SCREEN_NON_INTERACTIVE),
        )
        assertEquals(2 * min, a.totalMs(SegmentKind.SCREEN_USE))
    }

    @Test
    fun unlockedVideoAndOtherAppsAreSeparated() {
        val t = start + 30 * min
        val a = analyze(
            *lockAt(start - min),
            ev(t, SCREEN_INTERACTIVE),
            ev(t + 5 * sec, KEYGUARD_HIDDEN),
            ev(t + 5 * sec, ACTIVITY_RESUMED, youtube),
            ev(t + 20 * min, ACTIVITY_RESUMED, insta), // arrives before YouTube's PAUSED
            ev(t + 20 * min, ACTIVITY_PAUSED, youtube),
            ev(t + 25 * min, ACTIVITY_PAUSED, insta),
            *lockAt(t + 25 * min),
        )
        assertEquals(20 * min - 5 * sec, a.totalMs(SegmentKind.VIDEO))
        assertEquals(5 * min, a.totalMs(SegmentKind.SCREEN_USE))
        assertEquals(5 * sec, a.totalMs(SegmentKind.GLANCE)) // lock screen before unlocking
        assertEquals(insta, a.segments.single { it.kind == SegmentKind.SCREEN_USE }.packageName)
    }

    @Test
    fun mergedScreenUseIsNamedAfterLongestApp() {
        val t = start + 30 * min
        val a = analyze(
            *lockAt(start - min),
            ev(t, SCREEN_INTERACTIVE),
            ev(t, KEYGUARD_HIDDEN),
            ev(t, ACTIVITY_RESUMED, "a"),
            ev(t + 1 * min, ACTIVITY_RESUMED, insta),
            ev(t + 10 * min, ACTIVITY_RESUMED, "b"),
            *lockAt(t + 12 * min),
        )
        val use = a.segments.single()
        assertEquals(12 * min, use.durationMs)
        assertEquals(insta, use.packageName)
    }

    @Test
    fun usageIsClippedToWindowEnd() {
        val a = analyze(
            *lockAt(start - min),
            ev(end - 5 * min, SCREEN_INTERACTIVE),
            ev(end - 5 * min, KEYGUARD_HIDDEN),
            ev(end + 10 * min, SCREEN_NON_INTERACTIVE),
        )
        assertEquals(5 * min, a.totalMs(SegmentKind.SCREEN_USE))
    }

    @Test
    fun shutdownEndsScreenUse() {
        val a = analyze(
            ev(start - min, KEYGUARD_HIDDEN),
            ev(start + 3 * min, DEVICE_SHUTDOWN),
        )
        assertEquals(3 * min, a.totalMs(SegmentKind.SCREEN_USE))
    }

    @Test
    fun liveTrackingMediaWhileUnlockedIsVideo() {
        val t = start + 30 * min
        val a = analyze(
            *lockAt(start),
            ev(t, SCREEN_INTERACTIVE),
            ev(t, KEYGUARD_HIDDEN),
            ev(t + 2 * min, EventType.MEDIA_STARTED),
            *lockAt(t + 20 * min), // media keeps playing with the screen off: not video
            ev(t + 60 * min, SCREEN_INTERACTIVE),
            ev(t + 60 * min, KEYGUARD_HIDDEN),
            ev(t + 61 * min, EventType.MEDIA_STOPPED),
            *lockAt(t + 65 * min),
        )
        assertEquals(18 * min + 1 * min, a.totalMs(SegmentKind.VIDEO))
        assertEquals(2 * min + 4 * min, a.totalMs(SegmentKind.SCREEN_USE))
        assertEquals(null, a.segments.first { it.kind == SegmentKind.VIDEO }.packageName)
    }

    @Test
    fun zeroThresholdCountsEveryLockScreenWake() {
        val a = analyze(
            *lockAt(start - min),
            ev(start + 60 * min, SCREEN_INTERACTIVE),
            ev(start + 60 * min + 3 * sec, SCREEN_NON_INTERACTIVE),
            glance = 0,
        )
        assertEquals(3 * sec, a.totalMs(SegmentKind.SCREEN_USE))
    }
}
