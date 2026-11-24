package dev.aalhdydy550.tunnel.core.data.stats

import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Persisted usage statistics (§4-#8): what the live speed display
 * shows today disappears when the session ends — this is the part
 * that remembers.
 *
 * What is stored (private JSON file in internal storage, see
 * [UsageStatsStore] — NOT Room: the Room database is untouched):
 * - one record per session (profile, start/end, up/down bytes,
 *   duration),
 * - per-day totals (last 90 days),
 * - per-profile totals,
 * - all-time totals.
 *
 * Nothing here leaves the device, nothing is sampled per byte:
 * progress is noted in memory (see [SessionAccumulator]) and the
 * file is written once, when the session ends.
 */

@Serializable
data class SessionRecord(
    val profileName: String,
    val startedAtMs: Long,
    val endedAtMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
) {
    /** Session length in milliseconds. Never negative. */
    val durationMs: Long get() = (endedAtMs - startedAtMs).coerceAtLeast(0)
}

@Serializable
data class DailyUsage(
    val day: String,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val sessions: Int = 0,
)

@Serializable
data class ProfileUsage(
    val profileName: String,
    val rxBytes: Long = 0,
    val txBytes: Long = 0,
    val sessions: Int = 0,
)

@Serializable
data class UsageStatsState(
    val version: Int = 1,
    val sessions: List<SessionRecord> = emptyList(),
    val daily: Map<String, DailyUsage> = emptyMap(),
    val byProfile: Map<String, ProfileUsage> = emptyMap(),
    val allTimeRxBytes: Long = 0,
    val allTimeTxBytes: Long = 0,
    val totalSessions: Int = 0,
)

/** Totals for one window (today / this week / all time). */
data class UsageTotals(
    val rxBytes: Long,
    val txBytes: Long,
    val sessions: Int,
)

/** A session still running, with its in-memory counters. */
data class LiveSession(
    val profileName: String,
    val startedAtMs: Long,
    val rxBytes: Long,
    val txBytes: Long,
)

/** Everything the statistics screen needs, in one snapshot. */
data class UsageSnapshot(
    val today: UsageTotals,
    val week: UsageTotals,
    val allTime: UsageTotals,
    val currentSession: LiveSession?,
    val recentSessions: List<SessionRecord>,
    val byProfile: List<ProfileUsage>,
)

/**
 * Pure aggregation over [UsageStatsState]: no Android, no I/O, fully
 * unit-testable on the JVM.
 */
object UsageStatsAggregator {

    /** How many recent sessions are kept (file stays tiny). */
    const val MAX_SESSIONS = 30

    /** How many days of per-day totals are kept. */
    const val MAX_DAYS = 90

    /**
     * Day key for [epochMs] in the device's local timezone, so "today"
     * means the user's day, not a UTC day.
     */
    fun dayKey(epochMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        format.timeZone = TimeZone.getDefault()
        return format.format(Date(epochMs))
    }

    /** The 7 day keys (Monday..Sunday) of the week containing [nowMs]. */
    fun weekDayKeys(nowMs: Long): List<String> {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = nowMs
        calendar.firstDayOfWeek = Calendar.MONDAY
        calendar.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return (0..6).map { offset ->
            val day = (calendar.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, offset)
            }
            dayKey(day.timeInMillis)
        }
    }

    /** Folds one finished session into the stored state. */
    fun record(state: UsageStatsState, record: SessionRecord): UsageStatsState {
        val day = dayKey(record.startedAtMs)
        val previousDay = state.daily[day]
        val updatedDaily = (state.daily + (day to DailyUsage(
            day = day,
            rxBytes = (previousDay?.rxBytes ?: 0) + record.rxBytes,
            txBytes = (previousDay?.txBytes ?: 0) + record.txBytes,
            sessions = (previousDay?.sessions ?: 0) + 1,
        ))).let { map ->
            // Drop the oldest days once the cap is hit.
            if (map.size <= MAX_DAYS) map else map.entries
                .sortedBy { it.key }
                .takeLast(MAX_DAYS)
                .associate { it.key to it.value }
        }
        val previousProfile = state.byProfile[record.profileName]
        val updatedProfiles = state.byProfile + (record.profileName to ProfileUsage(
            profileName = record.profileName,
            rxBytes = (previousProfile?.rxBytes ?: 0) + record.rxBytes,
            txBytes = (previousProfile?.txBytes ?: 0) + record.txBytes,
            sessions = (previousProfile?.sessions ?: 0) + 1,
        ))
        return state.copy(
            sessions = (state.sessions + record).takeLast(MAX_SESSIONS),
            daily = updatedDaily,
            byProfile = updatedProfiles,
            allTimeRxBytes = state.allTimeRxBytes + record.rxBytes,
            allTimeTxBytes = state.allTimeTxBytes + record.txBytes,
            totalSessions = state.totalSessions + 1,
        )
    }

    /** Totals for the day containing [nowMs]. */
    fun todayTotals(state: UsageStatsState, nowMs: Long): UsageTotals {
        val day = state.daily[dayKey(nowMs)]
        return UsageTotals(
            rxBytes = day?.rxBytes ?: 0,
            txBytes = day?.txBytes ?: 0,
            sessions = day?.sessions ?: 0,
        )
    }

    /** Totals for the week (Monday..Sunday) containing [nowMs]. */
    fun weekTotals(state: UsageStatsState, nowMs: Long): UsageTotals {
        val keys = weekDayKeys(nowMs).toSet()
        var rx = 0L
        var tx = 0L
        var sessions = 0
        for ((day, usage) in state.daily) {
            if (day in keys) {
                rx += usage.rxBytes
                tx += usage.txBytes
                sessions += usage.sessions
            }
        }
        return UsageTotals(rxBytes = rx, txBytes = tx, sessions = sessions)
    }

    /** All-time totals. */
    fun allTimeTotals(state: UsageStatsState): UsageTotals =
        UsageTotals(
            rxBytes = state.allTimeRxBytes,
            txBytes = state.allTimeTxBytes,
            sessions = state.totalSessions,
        )

    /** Full snapshot for the statistics screen. */
    fun snapshot(
        state: UsageStatsState,
        nowMs: Long,
        currentSession: LiveSession?,
    ): UsageSnapshot = UsageSnapshot(
        today = todayTotals(state, nowMs),
        week = weekTotals(state, nowMs),
        allTime = allTimeTotals(state),
        currentSession = currentSession,
        recentSessions = state.sessions.sortedByDescending { it.startedAtMs },
        byProfile = state.byProfile.values
            .sortedByDescending { it.rxBytes + it.txBytes },
    )
}

/**
 * In-memory accumulator for the running session.
 *
 * The caller reports the *cumulative* UID counters (the same values
 * [SpeedMonitor] samples) whenever it already samples them — about
 * once every 2 seconds — and this class turns them into session
 * deltas. No I/O happens here at all: the totals only reach disk
 * when the session ends (see [UsageStatsRepository.endSession]).
 *
 * A counter that goes backwards (TrafficStats reset, UID reuse) is
 * treated as a reset: the delta is 0, never negative.
 */
class SessionAccumulator {

    private var profileName: String? = null
    private var startedAtMs: Long = 0L
    private var lastRx: Long? = null
    private var lastTx: Long? = null
    private var rxBytes: Long = 0L
    private var txBytes: Long = 0L

    val isActive: Boolean get() = profileName != null

    fun begin(profileName: String, nowMs: Long) {
        this.profileName = profileName
        startedAtMs = nowMs
        lastRx = null
        lastTx = null
        rxBytes = 0L
        txBytes = 0L
    }

    /**
     * Notes the current cumulative counters. Safe to call often;
     * only cheap long arithmetic happens here.
     */
    fun noteProgress(rxCumulative: Long, txCumulative: Long) {
        val previousRx = lastRx
        val previousTx = lastTx
        if (previousRx != null) {
            rxBytes += (rxCumulative - previousRx).coerceAtLeast(0)
        }
        if (previousTx != null) {
            txBytes += (txCumulative - previousTx).coerceAtLeast(0)
        }
        lastRx = rxCumulative
        lastTx = txCumulative
    }

    fun liveSession(): LiveSession? {
        val name = profileName ?: return null
        return LiveSession(
            profileName = name,
            startedAtMs = startedAtMs,
            rxBytes = rxBytes,
            txBytes = txBytes,
        )
    }

    /** Finishes the session and returns its record, or null when no
     * session is running. */
    fun end(nowMs: Long): SessionRecord? {
        val name = profileName ?: return null
        val record = SessionRecord(
            profileName = name,
            startedAtMs = startedAtMs,
            endedAtMs = nowMs,
            rxBytes = rxBytes,
            txBytes = txBytes,
        )
        profileName = null
        lastRx = null
        lastTx = null
        rxBytes = 0L
        txBytes = 0L
        return record
    }
}

/** Human-readable byte count: B → KB → MB → GB, one decimal. */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes ب"
    bytes < 1024 * 1024 -> "%.1f ك.ب".format(Locale.US, bytes / 1024.0)
    bytes < 1024 * 1024 * 1024 -> "%.1f م.ب".format(Locale.US, bytes / (1024.0 * 1024.0))
    else -> "%.1f ج.ب".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
}

/** Human-readable duration: seconds → minutes → hours. */
fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> "$hours س $minutes د"
        minutes > 0 -> "$minutes د $seconds ث"
        else -> "$seconds ث"
    }
}

/** Human-readable date + time for a session row (local time). */
fun formatSessionTime(epochMs: Long): String {
    val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    format.timeZone = TimeZone.getDefault()
    return format.format(Date(epochMs))
}
