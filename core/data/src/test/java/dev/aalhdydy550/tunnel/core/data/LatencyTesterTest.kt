package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.net.LatencyTester
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Real unit tests for [LatencyTester] against a local server socket:
 * a listening socket answers fast, a closed port answers null.
 *
 * Note: the sockets bind IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI too.
 */
class LatencyTesterTest {

    @Test
    fun `tcpPing returns a small latency for a listening local socket`() {
        ServerSocket(0, 0, InetAddress.getByName("::1")).use { server ->
            val ms = LatencyTester.tcpPing("::1", server.localPort)
            assertTrue(ms != null && ms >= 0)
        }
    }

    @Test
    fun `tcpPing returns null for a closed port`() {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        assertNull(LatencyTester.tcpPing("::1", closedPort, timeoutMs = 500))
    }

    @Test
    fun `testAll keeps order and marks unreachable profiles null`() = runTest {
        val open = ServerSocket(0, 0, InetAddress.getByName("::1"))
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        try {
            val profiles = listOf(
                dev.aalhdydy550.tunnel.core.data.model.Profile(
                    name = "open", type = dev.aalhdydy550.tunnel.core.data.model.ProfileType.VLESS,
                    server = "::1", port = open.localPort, outboundJson = "{}",
                ),
                dev.aalhdydy550.tunnel.core.data.model.Profile(
                    name = "closed", type = dev.aalhdydy550.tunnel.core.data.model.ProfileType.VLESS,
                    server = "::1", port = closedPort, outboundJson = "{}",
                ),
            )
            val results = LatencyTester.testAll(profiles, timeoutMs = 500)
            assertEquals(2, results.size)
            assertEquals("open", results[0].profile.name)
            assertTrue(results[0].latencyMs != null)
            assertNull(results[1].latencyMs)
        } finally {
            open.close()
        }
    }
}
