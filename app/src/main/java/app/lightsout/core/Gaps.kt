package app.lightsout.core

/** Time during a live-tracked night when the tracking service wasn't running. */
object Gaps {
    /** The service may take a moment to come up after the night starts. */
    private const val START_GRACE_MS = 60_000L

    /**
     * @param recordedMs times of every event the service recorded, including its starts
     * @param serviceStartsMs times the service started (once normally; again after being killed)
     * @param deathsMs times the app's process died, where Android reports them (Android 11+)
     */
    fun unmonitoredMs(
        windowStartMs: Long,
        windowEndMs: Long,
        sessionStartMs: Long,
        recordedMs: List<Long>,
        serviceStartsMs: List<Long>,
        deathsMs: List<Long>,
    ): Long {
        val starts = serviceStartsMs.sorted()
        if (starts.isEmpty()) return (windowEndMs - windowStartMs).coerceAtLeast(0)
        val gaps = mutableListOf<Pair<Long, Long>>()
        if (starts.first() > sessionStartMs + START_GRACE_MS) gaps += sessionStartMs to starts.first()
        for (restart in starts.drop(1)) {
            // The service died somewhere between the last thing it recorded and the restart.
            val lastSeen = recordedMs.filter { it < restart }.maxOrNull() ?: sessionStartMs
            val death = deathsMs.filter { it in lastSeen..restart }.minOrNull()
            gaps += (death ?: lastSeen) to restart
        }
        deathsMs.filter { it > starts.last() }.minOrNull()?.let { gaps += it to windowEndMs }
        return total(gaps, windowStartMs, windowEndMs)
    }

    private fun total(gaps: List<Pair<Long, Long>>, fromMs: Long, toMs: Long): Long {
        var sum = 0L
        var coveredUntil = fromMs
        for ((start, end) in gaps.sortedBy { it.first }) {
            val s = maxOf(start, coveredUntil)
            val e = minOf(end, toMs)
            if (e > s) {
                sum += e - s
                coveredUntil = e
            }
        }
        return sum
    }
}
