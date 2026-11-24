package dev.aalhdydy550.tunnel.feature.connection.injector

import dev.aalhdydy550.tunnel.feature.connection.FakeHttpServer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * [InjectorProbe] response-code logic against the local stub server —
 * no external network of any kind.
 *
 * Note: the server binds IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI
 * too (same pattern as FakeHttpServer's own documentation).
 */
class InjectorProbeTest {

    private val probe = InjectorProbe()

    @Test
    fun `204 means the tunnel passes traffic`() {
        val server = FakeHttpServer()
        server.route("/generate_204", 204)
        server.start()
        try {
            assertTrue(probe.probe("http://[::1]:${server.port}/generate_204"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `200 is not a pass`() {
        val server = FakeHttpServer()
        server.route("/generate_204", 200, "ok")
        server.start()
        try {
            assertFalse(probe.probe("http://[::1]:${server.port}/generate_204"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `404 is not a pass`() {
        val server = FakeHttpServer()
        server.route("/generate_204", 404)
        server.start()
        try {
            assertFalse(probe.probe("http://[::1]:${server.port}/generate_204"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `unreachable host is not a pass`() {
        val closedPort =
            ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        assertFalse(
            probe.probe(
                "http://[::1]:$closedPort/generate_204",
                timeoutMs = 2_000,
            ),
        )
    }
}
