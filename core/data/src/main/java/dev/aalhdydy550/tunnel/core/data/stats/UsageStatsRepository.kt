package dev.aalhdydy550.tunnel.core.data.stats

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Usage statistics repository (§4-#8).
 *
 * Lifecycle (driven by the VPN service — see the integration steps
 * in the delivery summary; this class itself starts no service and
 * schedules no periodic work):
 * - `beginSession(profileName)` when a tunnel starts,
 * - `noteProgress(rxCumulative, txCumulative)` on the sampling ticks
 *   the service already performs (~every 2 s) — in-memory only,
 *   no disk I/O,
 * - `endSession()` when the tunnel stops — the single disk write.
 *
 * A session interrupted by a system kill (no `endSession`) is lost:
 * that is the accepted trade-off for "write once per session"
 * instead of writing on every tick. Reconnects must NOT call
 * begin/end — one user session is one record even across reconnects.
 */
@Singleton
class UsageStatsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    companion object {
        const val FILE_NAME = "usage-stats.json"
    }

    private val store = UsageStatsStore(File(context.filesDir, FILE_NAME))
    private val mutex = Mutex()
    private val accumulator = SessionAccumulator()

    // Read on the noteProgress path without the lock (it only ever
    // replaces the whole snapshot input); @Volatile keeps the write
    // from endSession/clearAll visible to that path.
    @Volatile
    private var state = UsageStatsState()
    @Volatile
    private var loaded = false

    private val _snapshot = MutableStateFlow(
        UsageStatsAggregator.snapshot(state, System.currentTimeMillis(), null),
    )

    /** Current statistics for the UI; updated on every lifecycle call. */
    val snapshot: StateFlow<UsageSnapshot> = _snapshot.asStateFlow()

    private fun refreshSnapshotLocked() {
        _snapshot.value = UsageStatsAggregator.snapshot(
            state,
            System.currentTimeMillis(),
            accumulator.liveSession(),
        )
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (!loaded) {
                state = withContext(Dispatchers.IO) { store.load() }
                loaded = true
                refreshSnapshotLocked()
            }
        }
    }

    /** Reloads the persisted statistics (call once from the UI). */
    suspend fun refresh() {
        ensureLoaded()
        mutex.withLock { refreshSnapshotLocked() }
    }

    /** Starts recording a session. Ignored when one is already running
     * (a reconnect must not split the session). */
    suspend fun beginSession(profileName: String) {
        ensureLoaded()
        mutex.withLock {
            if (!accumulator.isActive) {
                accumulator.begin(profileName, System.currentTimeMillis())
                refreshSnapshotLocked()
            }
        }
    }

    /**
     * Notes the current cumulative UID counters (the same values the
     * live speed sampler reads). In-memory only — callers pass the
     * counters they already sample; this adds zero sampling work.
     */
    fun noteProgress(rxCumulative: Long, txCumulative: Long) {
        if (!accumulator.isActive) return
        accumulator.noteProgress(rxCumulative, txCumulative)
        _snapshot.value = UsageStatsAggregator.snapshot(
            state,
            System.currentTimeMillis(),
            accumulator.liveSession(),
        )
    }

    /**
     * Finishes the running session and persists it — the only disk
     * write in the normal flow. No-op when no session is running.
     */
    suspend fun endSession() {
        ensureLoaded()
        mutex.withLock {
            // No session running: nothing to persist.
            val finished = accumulator.end(System.currentTimeMillis()) ?: return
            state = UsageStatsAggregator.record(state, finished)
            refreshSnapshotLocked()
        }
        // Write outside the lock: a slow disk must never block the
        // next beginSession.
        withContext(Dispatchers.IO) {
            mutex.withLock { store.save(state) }
        }
    }

    /** Deletes all stored statistics (user action from the screen). */
    suspend fun clearAll() {
        ensureLoaded()
        mutex.withLock {
            accumulator.end(System.currentTimeMillis())
            state = UsageStatsState()
            refreshSnapshotLocked()
        }
        withContext(Dispatchers.IO) { store.clear() }
    }
}
