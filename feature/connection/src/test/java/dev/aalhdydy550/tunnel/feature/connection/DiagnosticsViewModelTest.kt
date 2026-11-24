package dev.aalhdydy550.tunnel.feature.connection

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DiagnosticsViewModel] and the pure helpers it
 * stands on ([classifyDiagnosticLog], [formatDiagnosticLog]).
 *
 * The ViewModel reads the global [TunnelEventLog] and
 * [TunnelStateHolder], so every test resets both first. Main is an
 * unconfined test dispatcher, so the ViewModel's `viewModelScope`
 * collection runs eagerly and assertions can read the StateFlows
 * synchronously.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        TunnelEventLog.clear()
        TunnelStateHolder.update(TunnelState.Stopped)
    }

    @After
    fun tearDown() {
        TunnelEventLog.clear()
        TunnelStateHolder.update(TunnelState.Stopped)
        Dispatchers.resetMain()
    }

    @Test
    fun `events are shown with kinds derived from the message`() {
        TunnelEventLog.log("starting: TestServer")
        TunnelEventLog.log("started: TestServer")
        TunnelEventLog.logDisconnect("timeout")
        TunnelEventLog.log("network changed (WiFi ↔ mobile)")
        TunnelEventLog.log("heartbeat: tunnel looks dead (x)")
        TunnelEventLog.log("something unexpected")

        val viewModel = DiagnosticsViewModel()

        assertEquals(6, viewModel.totalCount.value)
        assertEquals(
            listOf(
                DiagnosticLogKind.CONNECTED,
                DiagnosticLogKind.CONNECTED,
                DiagnosticLogKind.DISCONNECTED,
                DiagnosticLogKind.NETWORK,
                DiagnosticLogKind.HEALTH,
                DiagnosticLogKind.OTHER,
            ),
            viewModel.events.value.map { it.kind },
        )
        assertEquals("started: TestServer", viewModel.events.value[1].message)
    }

    @Test
    fun `filter narrows the list but not the total count`() {
        TunnelEventLog.log("started: TestServer")
        TunnelEventLog.logDisconnect("timeout")
        val viewModel = DiagnosticsViewModel()

        viewModel.setFilter(DiagnosticLogKind.DISCONNECTED)
        assertEquals(1, viewModel.events.value.size)
        assertEquals("disconnected: timeout", viewModel.events.value[0].message)
        assertEquals(2, viewModel.totalCount.value)

        viewModel.setFilter(null)
        assertEquals(2, viewModel.events.value.size)
    }

    @Test
    fun `clearLog empties the list and the engine log`() {
        TunnelEventLog.log("started: TestServer")
        val viewModel = DiagnosticsViewModel()
        assertEquals(1, viewModel.events.value.size)

        viewModel.clearLog()

        assertTrue(viewModel.events.value.isEmpty())
        assertEquals(0, viewModel.totalCount.value)
        assertTrue(TunnelEventLog.snapshot().isEmpty())
    }

    @Test
    fun `formattedLogText has one line per shown event`() {
        TunnelEventLog.log("started: TestServer")
        TunnelEventLog.logDisconnect("timeout")
        val viewModel = DiagnosticsViewModel()

        val text = viewModel.formattedLogText()

        assertEquals(2, text.lines().size)
        assertTrue(text.contains("[اتصال] started: TestServer"))
        assertTrue(text.contains("[انقطاع] disconnected: timeout"))

        viewModel.setFilter(DiagnosticLogKind.CONNECTED)
        assertEquals(1, viewModel.formattedLogText().lines().size)
    }

    @Test
    fun `formattedLogText is empty when there are no events`() {
        val viewModel = DiagnosticsViewModel()
        assertEquals("", viewModel.formattedLogText())
    }

    @Test
    fun `state changes refresh the list`() {
        val viewModel = DiagnosticsViewModel()
        assertTrue(viewModel.events.value.isEmpty())

        TunnelEventLog.log("started: TestServer")
        TunnelStateHolder.update(TunnelState.Started("TestServer"))

        assertEquals(1, viewModel.events.value.size)
        TunnelStateHolder.update(TunnelState.Stopped)
    }

    @Test
    fun `classify covers every known prefix`() {
        assertEquals(
            DiagnosticLogKind.CONNECTED,
            classifyDiagnosticLog("starting: S"),
        )
        assertEquals(
            DiagnosticLogKind.CONNECTED,
            classifyDiagnosticLog("started: S"),
        )
        assertEquals(
            DiagnosticLogKind.CONNECTED,
            classifyDiagnosticLog("reconnected: S"),
        )
        assertEquals(
            DiagnosticLogKind.CONNECTED,
            classifyDiagnosticLog("reviving last session after system kill"),
        )
        assertEquals(
            DiagnosticLogKind.DISCONNECTED,
            classifyDiagnosticLog("disconnected: timeout"),
        )
        assertEquals(
            DiagnosticLogKind.NETWORK,
            classifyDiagnosticLog("network changed (WiFi ↔ mobile)"),
        )
        assertEquals(
            DiagnosticLogKind.HEALTH,
            classifyDiagnosticLog("heartbeat: tunnel looks dead (x)"),
        )
        assertEquals(
            DiagnosticLogKind.HEALTH,
            classifyDiagnosticLog("فحص ما بعد الاتصال: ناجح — IP 1.2.3.4 — 10ms"),
        )
        assertEquals(
            DiagnosticLogKind.OTHER,
            classifyDiagnosticLog("???"),
        )
    }
}
