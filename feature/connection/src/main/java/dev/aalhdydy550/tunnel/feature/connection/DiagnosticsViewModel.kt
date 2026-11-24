package dev.aalhdydy550.tunnel.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Kind of a tunnel event, derived from the message text: the engine
 * ([TunnelEventLog]) stores plain messages only, with no type or
 * level field, so the kind is classified here from the message
 * prefix (see [classifyDiagnosticLog]). The prefixes come from the
 * call sites in TunnelVpnService ("starting:", "started:",
 * "disconnected:", "reconnected:", "network changed", "heartbeat:",
 * "reviving…") and from [PostConnectionCheckViewModel]
 * ("فحص ما بعد الاتصال:").
 */
enum class DiagnosticLogKind(val label: String) {
    CONNECTED("اتصال"),
    DISCONNECTED("انقطاع"),
    NETWORK("شبكة"),
    HEALTH("فحص"),
    OTHER("أخرى"),
}

/** Pure classification of one log message — unit-tested. */
fun classifyDiagnosticLog(message: String): DiagnosticLogKind = when {
    message.startsWith("disconnected:") -> DiagnosticLogKind.DISCONNECTED
    message.startsWith("starting:") ||
        message.startsWith("started:") ||
        message.startsWith("reconnected:") ||
        message.startsWith("reviving") -> DiagnosticLogKind.CONNECTED
    message.startsWith("network changed") -> DiagnosticLogKind.NETWORK
    message.startsWith("heartbeat:") ||
        message.startsWith("فحص ما بعد الاتصال:") -> DiagnosticLogKind.HEALTH
    else -> DiagnosticLogKind.OTHER
}

data class DiagnosticLogRow(
    val timeMs: Long,
    val timeText: String,
    val kind: DiagnosticLogKind,
    val message: String,
)

/**
 * Plain-text rendering of the (already filtered) rows, one event per
 * line — what "نسخ السجل" puts on the clipboard. Unit-tested.
 */
fun formatDiagnosticLog(rows: List<DiagnosticLogRow>): String =
    rows.joinToString("\n") { "[${it.timeText}] [${it.kind.label}] ${it.message}" }

/**
 * Diagnostics screen logic (#9) over the existing [TunnelEventLog]
 * ring buffer (in-memory, last 100 events — no file is ever written).
 *
 * The log is not a Flow, so the list refreshes on: screen open,
 * filter change, manual refresh, and every tunnel-state change
 * (events almost always accompany one). No periodic polling — nothing
 * ticks while the screen is open, so this costs no battery.
 * The list is capped at 100 rows by the engine, and the screen shows
 * it with a LazyColumn, so a 2 GB phone never holds a big list.
 */
@HiltViewModel
class DiagnosticsViewModel @Inject constructor() : ViewModel() {

    private val _filter = MutableStateFlow<DiagnosticLogKind?>(null)
    val filter: StateFlow<DiagnosticLogKind?> = _filter.asStateFlow()

    private val _events = MutableStateFlow<List<DiagnosticLogRow>>(emptyList())
    val events: StateFlow<List<DiagnosticLogRow>> = _events.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    private val _copied = MutableStateFlow(false)
    val copied: StateFlow<Boolean> = _copied.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            TunnelStateHolder.state.collect { refresh() }
        }
    }

    fun refresh() {
        val snapshot = TunnelEventLog.snapshot()
        _totalCount.value = snapshot.size
        val wanted = _filter.value
        _events.value = snapshot.map { event ->
            DiagnosticLogRow(
                timeMs = event.timeMs,
                timeText = event.formattedTime,
                kind = classifyDiagnosticLog(event.message),
                message = event.message,
            )
        }.filter { wanted == null || it.kind == wanted }
    }

    fun setFilter(kind: DiagnosticLogKind?) {
        _filter.value = kind
        _copied.value = false
        refresh()
    }

    /**
     * Clears the in-memory ring only. The Logcat mirror survives
     * (TunnelEventLog mirrors every event to Logcat), so a cleared
     * screen is not a lost debug trail.
     */
    fun clearLog() {
        TunnelEventLog.clear()
        _copied.value = false
        refresh()
    }

    /** The text the screen copies: the currently shown (filtered) rows. */
    fun formattedLogText(): String = formatDiagnosticLog(_events.value)

    fun markCopied() {
        _copied.value = true
    }
}
