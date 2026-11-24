package dev.aalhdydy550.tunnel.feature.connection.injector

import dev.aalhdydy550.tunnel.feature.connection.FakeHttpServer
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.HostScanner
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.ScanStatus
import java.net.InetAddress
import java.net.ServerSocket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [HostScanner] against the local stub server — no external network
 * of any kind.
 *
 * The server binds IPv6 loopback (::1) — on this dev VM the sandbox
 * fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1) and no
 * data flows there, while ::1 flows for real; ::1 works in CI too
 * (same pattern as [FakeHttpServer]'s own documentation).
 */
class HostScannerTest {

    @Test
    fun `200 host is SUCCESS with a measured latency`() {
        val server = FakeHttpServer()
        server.route("good.example.com:443", 200)
        server.start()
        try {
            val scanner = HostScanner(proxyHost = "::1", proxyPorts = listOf(server.port))
            val result = scanner.checkOne("good.example.com")
            assertEquals(ScanStatus.SUCCESS, result.status)
            assertEquals("good.example.com", result.host)
            assertEquals(server.port, result.proxyPort)
            assertTrue((result.latencyMs ?: -1) >= 0)
            assertTrue(result.checkedAt > 0)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `403 host is FAILED`() {
        val server = FakeHttpServer()
        server.route("bad.example.com:443", 403)
        server.start()
        try {
            val scanner = HostScanner(proxyHost = "::1", proxyPorts = listOf(server.port))
            val result = scanner.checkOne("bad.example.com")
            assertEquals(ScanStatus.FAILED, result.status)
            assertEquals(server.port, result.proxyPort)
            assertTrue((result.latencyMs ?: -1) >= 0)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unreachable proxy port is FAILED with no latency`() {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        val scanner = HostScanner(proxyHost = "::1", proxyPorts = listOf(closedPort))
        val result = scanner.checkOne("any.example.com")
        assertEquals(ScanStatus.FAILED, result.status)
        assertNull(result.latencyMs)
    }

    @Test
    fun `silent proxy is TIMEOUT`() {
        val server = FakeHttpServer()
        server.route("slow.example.com:443", 200, delayMs = 2_000)
        server.start()
        try {
            val scanner = HostScanner(
                proxyHost = "::1",
                proxyPorts = listOf(server.port),
                timeoutMs = 300,
            )
            val result = scanner.checkOne("slow.example.com")
            assertEquals(ScanStatus.TIMEOUT, result.status)
            assertNull(result.latencyMs)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `second proxy port is tried when the first refuses`() {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        val server = FakeHttpServer()
        server.route("good.example.com:443", 200)
        server.start()
        try {
            val scanner = HostScanner(
                proxyHost = "::1",
                proxyPorts = listOf(closedPort, server.port),
            )
            val result = scanner.checkOne("good.example.com")
            assertEquals(ScanStatus.SUCCESS, result.status)
            assertEquals(server.port, result.proxyPort)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `scan returns one result per host sorted by host`() = runTest {
        val server = FakeHttpServer()
        server.route("b.example.com:443", 200)
        server.route("a.example.com:443", 403)
        server.start()
        try {
            val scanner = HostScanner(proxyHost = "::1", proxyPorts = listOf(server.port))
            val results = scanner.scan(
                listOf("b.example.com", "a.example.com", "b.example.com", "  ", ""),
            )
            assertEquals(listOf("a.example.com", "b.example.com"), results.map { it.host })
            assertEquals(ScanStatus.FAILED, results[0].status)
            assertEquals(ScanStatus.SUCCESS, results[1].status)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `scanner rejects bad parameters`() {
        for (bad in listOf(
            { HostScanner(proxyHost = "") },
            { HostScanner(proxyHost = "::1", proxyPorts = emptyList()) },
            { HostScanner(proxyHost = "::1", proxyPorts = listOf(0)) },
            { HostScanner(proxyHost = "::1", timeoutMs = 0) },
            { HostScanner(proxyHost = "::1", maxParallel = 0) },
        )) {
            try {
                bad()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)
}
