package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.net.DnsSelector
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Unit tests for [DnsSelector] against local server sockets: a
 * listening socket is "fastest", a closed port is ignored, and
 * all-closed returns null (the caller then uses the fallback).
 *
 * Note: the sockets bind IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI too.
 */
class DnsSelectorTest {

    @Test
    fun `selectFastest picks the reachable resolver`() = runTest {
        val open = ServerSocket(0, 0, InetAddress.getByName("::1"))
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        try {
            val candidates = listOf(
                DnsSelector.Resolver(
                    name = "closed",
                    probeHost = "::1",
                    probePort = closedPort,
                    dotAddress = "tls://[::1]",
                ),
                DnsSelector.Resolver(
                    name = "open",
                    probeHost = "::1",
                    probePort = open.localPort,
                    dotAddress = "tls://[::1]",
                ),
            )
            assertEquals("open", DnsSelector.selectFastest(candidates)?.name)
        } finally {
            open.close()
        }
    }

    @Test
    fun `selectFastest returns null when nothing answers`() = runTest {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        val candidates = listOf(
            DnsSelector.Resolver(
                name = "dead",
                probeHost = "::1",
                probePort = closedPort,
                dotAddress = "tls://[::1]",
            ),
        )
        assertNull(DnsSelector.selectFastest(candidates))
    }

    @Test
    fun `candidates are the original app's public resolvers`() {
        val addresses = DnsSelector.CANDIDATES.map { it.dotAddress }
        assertEquals(
            listOf("tls://1.1.1.1", "tls://223.5.5.5", "tls://dns.google"),
            addresses,
        )
        assertEquals("tls://1.1.1.1", DnsSelector.FALLBACK_DOT_ADDRESS)
    }
}
