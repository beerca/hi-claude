package app.lightsout.core

import org.junit.Assert.assertEquals
import org.junit.Test

class GapsTest {
    private val min = 60_000L
    private val start = 1_000_000 * min
    private val end = start + 8 * 60 * min

    private fun gaps(recorded: List<Long>, starts: List<Long>, deaths: List<Long> = emptyList(), from: Long = start) =
        Gaps.unmonitoredMs(from, end, start, recorded + starts, starts, deaths)

    @Test
    fun serviceRunningAllNightHasNoGap() {
        assertEquals(0L, gaps(recorded = listOf(start + 5 * min, start + 90 * min), starts = listOf(start)))
    }

    @Test
    fun serviceNeverStartedMeansWholeWindow() {
        assertEquals(end - start, gaps(recorded = emptyList(), starts = emptyList()))
    }

    @Test
    fun lateFirstStartIsAGap() {
        assertEquals(30 * min, gaps(recorded = emptyList(), starts = listOf(start + 30 * min)))
    }

    @Test
    fun restartWithKnownDeathCountsFromDeath() {
        val result = gaps(
            recorded = listOf(start + 10 * min),
            starts = listOf(start, start + 120 * min),
            deaths = listOf(start + 100 * min),
        )
        assertEquals(20 * min, result)
    }

    @Test
    fun restartWithoutDeathInfoCountsFromLastEvent() {
        val result = gaps(recorded = listOf(start + 10 * min), starts = listOf(start, start + 120 * min))
        assertEquals(110 * min, result)
    }

    @Test
    fun deathWithoutRestartRunsToEnd() {
        val result = gaps(recorded = emptyList(), starts = listOf(start), deaths = listOf(end - 60 * min))
        assertEquals(60 * min, result)
    }

    @Test
    fun gapsAreClippedToCountedWindow() {
        val result = gaps(recorded = emptyList(), starts = listOf(start + 30 * min), from = start + 20 * min)
        assertEquals(10 * min, result)
    }

    @Test
    fun overlappingGapsCountOnce() {
        val result = gaps(
            recorded = emptyList(),
            starts = listOf(start, start + 60 * min),
            deaths = listOf(start + 30 * min, start + 90 * min),
        )
        // 30..60 from the first death, 90..end from the second.
        assertEquals(30 * min + (end - start - 90 * min), result)
    }
}
