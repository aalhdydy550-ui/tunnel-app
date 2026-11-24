package dev.aalhdydy550.tunnel.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** Pure JVM tests for the statistics aggregation logic (§4-#8). */
class UsageStatsAggregatorTest {

    /** Noon on a Wednesday, local timezone: yesterday is still the
     * same week, 10 days ago is not. */
    private fun wednesdayNoon(): Long {
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.WEDNESDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 12)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.timeInMillis
    }

    private fun session(
        name: String,
        startedAtMs: Long,
        durationMs: Long = 60_000,
        rx: Long = 1_000,
        tx: Long = 500,
    ) = SessionRecord(
        profileName = name,
        startedAtMs = startedAtMs,
        endedAtMs = startedAtMs + durationMs,
        rxBytes = rx,
        txBytes = tx,
    )

    @Test
    fun `one session lands in today week all-time and per-profile`() {
        val now = wednesdayNoon()
        val state = UsageStatsAggregator.record(
            UsageStatsState(),
            session("Server A", now - 1_000),
        )
        val today = UsageStatsAggregator.todayTotals(state, now)
        assertEquals(1_000L, today.rxBytes)
        assertEquals(500L, today.txBytes)
        assertEquals(1, today.sessions)
        val week = UsageStatsAggregator.weekTotals(state, now)
        assertEquals(1_000L, week.rxBytes)
        assertEquals(1, week.sessions)
        val all = UsageStatsAggregator.allTimeTotals(state)
        assertEquals(1_000L, all.rxBytes)
        assertEquals(1, all.sessions)
        assertEquals(1, state.byProfile.size)
        assertEquals(1_000L, state.byProfile["Server A"]!!.rxBytes)
        assertEquals(1, state.sessions.size)
    }

    @Test
    fun `two sessions on the same day add up`() {
        val now = wednesdayNoon()
        var state = UsageStatsState()
        state = UsageStatsAggregator.record(state, session("A", now - 2_000, rx = 1_000))
        state = UsageStatsAggregator.record(state, session("B", now - 1_000, rx = 2_000))
        val today = UsageStatsAggregator.todayTotals(state, now)
        assertEquals(3_000L, today.rxBytes)
        assertEquals(2, today.sessions)
        assertEquals(2, state.totalSessions)
    }

    @Test
    fun `yesterday counts for the week but not for today`() {
        val now = wednesdayNoon()
        val yesterday = now - 24 * 60 * 60 * 1000L
        val state = UsageStatsAggregator.record(
            UsageStatsState(),
            session("A", yesterday, rx = 7_000, tx = 3_000),
        )
        val today = UsageStatsAggregator.todayTotals(state, now)
        assertEquals(0L, today.rxBytes)
        assertEquals(0, today.sessions)
        val week = UsageStatsAggregator.weekTotals(state, now)
        assertEquals(7_000L, week.rxBytes)
        assertEquals(3_000L, week.txBytes)
        assertEquals(1, week.sessions)
    }

    @Test
    fun `ten days ago counts only for all-time`() {
        val now = wednesdayNoon()
        val old = now - 10L * 24 * 60 * 60 * 1000
        val state = UsageStatsAggregator.record(
            UsageStatsState(),
            session("A", old, rx = 9_000),
        )
        assertEquals(0L, UsageStatsAggregator.todayTotals(state, now).rxBytes)
        assertEquals(0L, UsageStatsAggregator.weekTotals(state, now).rxBytes)
        assertEquals(9_000L, UsageStatsAggregator.allTimeTotals(state).rxBytes)
    }

    @Test
    fun `session list is capped at the newest 30`() {
        val now = wednesdayNoon()
        var state = UsageStatsState()
        for (i in 0 until 35) {
            state = UsageStatsAggregator.record(
                state,
                session("S$i", now - i * 1_000L, rx = i.toLong()),
            )
        }
        assertEquals(UsageStatsAggregator.MAX_SESSIONS, state.sessions.size)
        // The oldest five are gone; the newest is kept.
        assertFalse(state.sessions.any { it.profileName == "S0" })
        assertFalse(state.sessions.any { it.profileName == "S4" })
        assertTrue(state.sessions.any { it.profileName == "S34" })
        // All-time totals still count every session.
        assertEquals(35, state.totalSessions)
    }

    @Test
    fun `daily map is capped at 90 days`() {
        val now = wednesdayNoon()
        var state = UsageStatsState()
        for (i in 0 until 100) {
            val dayStart = now - i.toLong() * 24 * 60 * 60 * 1000
            state = UsageStatsAggregator.record(state, session("A", dayStart))
        }
        assertEquals(UsageStatsAggregator.MAX_DAYS, state.daily.size)
        assertEquals(100, state.totalSessions)
    }

    @Test
    fun `week keys are Monday to Sunday of the current week`() {
        val now = wednesdayNoon()
        val keys = UsageStatsAggregator.weekDayKeys(now)
        assertEquals(7, keys.size)
        assertEquals(UsageStatsAggregator.dayKey(now), keys[2])
        // Consecutive days, no duplicates.
        assertEquals(7, keys.toSet().size)
        val monday = keys[0]
        val sunday = keys[6]
        assertTrue(monday < sunday)
    }

    @Test
    fun `accumulator turns cumulative counters into session deltas`() {
        val accumulator = SessionAccumulator()
        val now = wednesdayNoon()
        accumulator.begin("Server A", now)
        accumulator.noteProgress(10_000, 5_000) // baseline: no delta yet
        accumulator.noteProgress(13_000, 6_000)
        accumulator.noteProgress(15_000, 6_500)
        val live = accumulator.liveSession()!!
        assertEquals(5_000L, live.rxBytes)
        assertEquals(1_500L, live.txBytes)
        val record = accumulator.end(now + 60_000)!!
        assertEquals("Server A", record.profileName)
        assertEquals(5_000L, record.rxBytes)
        assertEquals(1_500L, record.txBytes)
        assertEquals(60_000L, record.durationMs)
        assertNull(accumulator.end(now + 61_000))
        assertFalse(accumulator.isActive)
    }

    @Test
    fun `accumulator ignores a counter that goes backwards`() {
        val accumulator = SessionAccumulator()
        accumulator.begin("A", wednesdayNoon())
        accumulator.noteProgress(10_000, 5_000)
        // TrafficStats reset (or UID reuse): counters drop.
        accumulator.noteProgress(2_000, 1_000)
        accumulator.noteProgress(4_000, 2_000)
        val live = accumulator.liveSession()!!
        // Only the 2,000 / 1,000 after the reset count; nothing goes
        // negative.
        assertEquals(2_000L, live.rxBytes)
        assertEquals(1_000L, live.txBytes)
    }

    @Test
    fun `snapshot orders recent sessions newest first`() {
        val now = wednesdayNoon()
        var state = UsageStatsState()
        state = UsageStatsAggregator.record(state, session("Old", now - 10_000))
        state = UsageStatsAggregator.record(state, session("New", now - 1_000))
        val snapshot = UsageStatsAggregator.snapshot(state, now, null)
        assertEquals("New", snapshot.recentSessions[0].profileName)
        assertEquals("Old", snapshot.recentSessions[1].profileName)
        assertNull(snapshot.currentSession)
    }

    @Test
    fun `formatBytes uses Arabic units with one decimal`() {
        assertEquals("0 ب", formatBytes(0))
        assertEquals("512 ب", formatBytes(512))
        assertEquals("1.0 ك.ب", formatBytes(1024))
        assertEquals("1.5 ك.ب", formatBytes(1536))
        assertEquals("1.0 م.ب", formatBytes(1024 * 1024))
        assertEquals("2.0 ج.ب", formatBytes(2L * 1024 * 1024 * 1024))
    }

    @Test
    fun `formatDuration uses seconds minutes hours`() {
        assertEquals("0 ث", formatDuration(0))
        assertEquals("45 ث", formatDuration(45_000))
        assertEquals("1 د 30 ث", formatDuration(90_000))
        assertEquals("1 س 1 د", formatDuration(3_661_000))
    }
}
