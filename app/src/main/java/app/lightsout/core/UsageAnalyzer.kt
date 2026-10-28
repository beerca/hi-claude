package app.lightsout.core

/**
 * Event type codes, identical to the android.app.usage.UsageEvents.Event constants.
 * Duplicated so this file stays plain Kotlin and can be unit-tested on the JVM.
 */
object EventType {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27

    val RELEVANT = setOf(
        ACTIVITY_RESUMED, ACTIVITY_PAUSED, SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE,
        KEYGUARD_SHOWN, KEYGUARD_HIDDEN, ACTIVITY_STOPPED, DEVICE_SHUTDOWN, DEVICE_STARTUP,
    )
}

data class RawEvent(val timeMs: Long, val type: Int, val packageName: String? = null)

enum class SegmentKind {
    /** Unlocked phone, or lock screen on for at least the glance threshold. Subtracted. */
    SCREEN_USE,

    /** Unlocked with a video app in the foreground. Subtracted unless video counts tonight. */
    VIDEO,

    /** Short lock-screen wake (checking the time, a notification lighting up). Not subtracted. */
    GLANCE,
}

data class Segment(val startMs: Long, val endMs: Long, val kind: SegmentKind, val packageName: String?) {
    val durationMs: Long get() = endMs - startMs
}

data class Analysis(val segments: List<Segment>) {
    fun totalMs(kind: SegmentKind): Long = segments.filter { it.kind == kind }.sumOf { it.durationMs }
}

object UsageAnalyzer {
    /**
     * Splits the screen-on time within [startMs, endMs) into segments.
     *
     * Events before [startMs] are replayed to recover the screen state at the start, so callers
     * should query some history before the window. Without any history the screen is assumed to
     * be on and unlocked, which is what it is when the user has just tapped "start".
     */
    fun analyze(
        events: List<RawEvent>,
        startMs: Long,
        endMs: Long,
        videoPackages: Set<String>,
        glanceThresholdMs: Long,
    ): Analysis {
        val state = ScreenState()
        val pieces = mutableListOf<Piece>()
        var cursor = startMs
        for (event in events.sortedBy { it.timeMs }) {
            if (event.timeMs >= endMs) break
            if (event.timeMs > cursor) {
                state.piece(cursor, event.timeMs, videoPackages)?.let(pieces::add)
                cursor = event.timeMs
            }
            state.apply(event)
        }
        if (endMs > cursor) state.piece(cursor, endMs, videoPackages)?.let(pieces::add)
        return Analysis(merge(pieces).map { it.classify(glanceThresholdMs) })
    }

    private enum class PieceKind { LOCKED, UNLOCKED, VIDEO }

    private data class Piece(
        val startMs: Long,
        val endMs: Long,
        val kind: PieceKind,
        val packageName: String?,
        /** Duration of the longest constituent piece, whose package names a merged run. */
        val packageMs: Long = endMs - startMs,
    ) {
        val durationMs: Long get() = endMs - startMs

        fun classify(glanceThresholdMs: Long): Segment = when (kind) {
            PieceKind.LOCKED -> Segment(
                startMs, endMs,
                if (durationMs < glanceThresholdMs) SegmentKind.GLANCE else SegmentKind.SCREEN_USE,
                null,
            )
            PieceKind.UNLOCKED -> Segment(startMs, endMs, SegmentKind.SCREEN_USE, packageName)
            PieceKind.VIDEO -> Segment(startMs, endMs, SegmentKind.VIDEO, packageName)
        }
    }

    private class ScreenState {
        var interactive = true
        var locked = false
        var foreground: String? = null

        fun apply(event: RawEvent) {
            when (event.type) {
                EventType.SCREEN_INTERACTIVE -> interactive = true
                EventType.SCREEN_NON_INTERACTIVE -> interactive = false
                EventType.KEYGUARD_SHOWN -> locked = true
                EventType.KEYGUARD_HIDDEN -> locked = false
                EventType.ACTIVITY_RESUMED -> foreground = event.packageName
                // The next app's RESUMED can arrive before the previous app's PAUSED.
                EventType.ACTIVITY_PAUSED, EventType.ACTIVITY_STOPPED ->
                    if (event.packageName == foreground) foreground = null
                EventType.DEVICE_SHUTDOWN, EventType.DEVICE_STARTUP -> {
                    interactive = false
                    locked = true
                    foreground = null
                }
            }
        }

        fun piece(startMs: Long, endMs: Long, videoPackages: Set<String>): Piece? = when {
            !interactive -> null
            locked -> Piece(startMs, endMs, PieceKind.LOCKED, null)
            foreground in videoPackages -> Piece(startMs, endMs, PieceKind.VIDEO, foreground)
            else -> Piece(startMs, endMs, PieceKind.UNLOCKED, foreground)
        }
    }

    private fun merge(pieces: List<Piece>): List<Piece> {
        val merged = mutableListOf<Piece>()
        for (piece in pieces) {
            val last = merged.lastOrNull()
            val joins = last != null && last.endMs == piece.startMs && last.kind == piece.kind &&
                (piece.kind != PieceKind.VIDEO || last.packageName == piece.packageName)
            if (!joins) {
                merged += piece
                continue
            }
            val longer = if (piece.durationMs > last!!.packageMs) piece.packageName to piece.durationMs
            else last.packageName to last.packageMs
            merged[merged.lastIndex] = last.copy(
                endMs = piece.endMs, packageName = longer.first, packageMs = longer.second,
            )
        }
        return merged
    }
}
