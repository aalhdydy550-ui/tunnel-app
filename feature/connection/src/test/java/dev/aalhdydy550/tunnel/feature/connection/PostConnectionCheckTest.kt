package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.net.EndpointProbe
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * Unit tests for [PostConnectionChecker] against the local
 * [FakeHttpServer] — no live network: the 204 endpoint and the IP
 * endpoint are both stubbed on IPv6 loopback (::1), the same pattern
 * [HeartbeatMonitorTest] uses.
 */
class PostConnectionCheckTest {

    private fun checkerFor(server: FakeHttpServer): PostConnectionChecker =
        PostConnectionChecker(
            client = OkHttpClient(),
            probeEndpoint = EndpointProbe.ProbeEndpoint(
                "test",
                "http://[::1]:${server.port}/generate_204",
            ),
            ipUrl = "http://[::1]:${server.port}/ip",
        )

    @Test
    fun `check succeeds with public IP and latency when 204 answers`() = runTest {
        val server = FakeHttpServer()
        server.route("/generate_204", 204)
        server.route("/ip", 200, "203.0.113.7")
        server.start()
        try {
            val result = checkerFor(server).check()
            assertTrue(result.success)
            assertEquals("203.0.113.7", result.publicIp)
            assertNotNull(result.latencyMs)
            assertTrue(result.latencyMs!! >= 0)
            assertEquals("الترافيك يمر عبر النفق", result.detail)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `check fails when generate_204 answers 500`() = runTest {
        val server = FakeHttpServer()
        server.route("/generate_204", 500)
        server.route("/ip", 200, "203.0.113.7")
        server.start()
        try {
            val result = checkerFor(server).check()
            assertFalse(result.success)
            assertNull(result.publicIp)
            assertNull(result.latencyMs)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `check fails when the server is down`() = runTest {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        val checker = PostConnectionChecker(
            client = OkHttpClient(),
            probeEndpoint = EndpointProbe.ProbeEndpoint(
                "test",
                "http://[::1]:$closedPort/generate_204",
            ),
            ipUrl = "http://[::1]:$closedPort/ip",
        )
        val result = checker.check()
        assertFalse(result.success)
        assertNull(result.publicIp)
        assertNull(result.latencyMs)
    }

    @Test
    fun `check still succeeds when the IP lookup fails`() = runTest {
        val server = FakeHttpServer()
        server.route("/generate_204", 204)
        server.route("/ip", 500)
        server.start()
        try {
            val result = checkerFor(server).check()
            // Traffic flows — the core question — so this is a success
            // with an unknown IP, not a broken tunnel.
            assertTrue(result.success)
            assertNull(result.publicIp)
            assertNotNull(result.latencyMs)
            assertTrue(result.detail.contains("تعذّر جلب الـIP العام"))
        } finally {
            server.stop()
        }
    }

    @Test
    fun `check rejects an HTML error page as an IP`() = runTest {
        val server = FakeHttpServer()
        server.route("/generate_204", 204)
        server.route("/ip", 200, "<html>not an ip</html>")
        server.start()
        try {
            val result = checkerFor(server).check()
            assertTrue(result.success)
            assertNull(result.publicIp)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `isValidIp accepts IPv4 and IPv6 and rejects junk`() {
        assertTrue(PostConnectionChecker.isValidIp("203.0.113.7"))
        assertTrue(PostConnectionChecker.isValidIp("2001:db8::1"))
        assertTrue(PostConnectionChecker.isValidIp("::ffff:203.0.113.7"))
        assertFalse(PostConnectionChecker.isValidIp(""))
        assertFalse(PostConnectionChecker.isValidIp("999.1.1.1"))
        assertFalse(PostConnectionChecker.isValidIp("203.0.113"))
        assertFalse(PostConnectionChecker.isValidIp("<html>not an ip</html>"))
        assertFalse(PostConnectionChecker.isValidIp("203.0.113.7\nsecond line"))
        assertFalse(PostConnectionChecker.isValidIp("203.0.113.7 "))
    }
}
