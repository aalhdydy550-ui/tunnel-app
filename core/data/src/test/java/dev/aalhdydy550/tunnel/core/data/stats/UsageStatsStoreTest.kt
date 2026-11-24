package dev.aalhdydy550.tunnel.core.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** JVM tests for the statistics file store (§4-#8). */
class UsageStatsStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun store() = UsageStatsStore(File(tempFolder.newFolder(), "usage-stats.json"))

    private fun sampleState() = UsageStatsState(
        sessions = listOf(
            SessionRecord(
                profileName = "Server A",
                startedAtMs = 1_700_000_000_000,
                endedAtMs = 1_700_000_060_000,
                rxBytes = 1_000,
                txBytes = 500,
            ),
        ),
        daily = mapOf(
            "2026-11-24" to DailyUsage(
                day = "2026-11-24",
                rxBytes = 1_000,
                txBytes = 500,
                sessions = 1,
            ),
        ),
        byProfile = mapOf(
            "Server A" to ProfileUsage(
                profileName = "Server A",
                rxBytes = 1_000,
                txBytes = 500,
                sessions = 1,
            ),
        ),
        allTimeRxBytes = 1_000,
        allTimeTxBytes = 500,
        totalSessions = 1,
    )

    @Test
    fun `missing file loads as empty state`() {
        val loaded = store().load()
        assertEquals(UsageStatsState(), loaded)
        assertTrue(loaded.sessions.isEmpty())
    }

    @Test
    fun `save then load round-trips the whole state`() {
        val store = store()
        store.save(sampleState())
        val loaded = store.load()
        assertEquals(sampleState(), loaded)
    }

    @Test
    fun `a corrupt file loads as empty state instead of throwing`() {
        val file = File(tempFolder.newFolder(), "usage-stats.json")
        file.writeText("{not valid json!!!")
        val loaded = UsageStatsStore(file).load()
        assertEquals(UsageStatsState(), loaded)
    }

    @Test
    fun `clear removes the file and load goes back to empty`() {
        val store = store()
        store.save(sampleState())
        assertEquals(1, store.load().totalSessions)
        store.clear()
        assertEquals(UsageStatsState(), store.load())
    }

    @Test
    fun `saving twice keeps the latest state`() {
        val store = store()
        store.save(sampleState())
        val newer = sampleState().copy(totalSessions = 2, allTimeRxBytes = 2_000)
        store.save(newer)
        assertEquals(newer, store.load())
    }
}
