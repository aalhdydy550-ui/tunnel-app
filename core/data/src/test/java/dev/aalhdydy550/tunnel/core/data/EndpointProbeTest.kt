package dev.aalhdydy550.tunnel.core.data

import com.sun.net.httpserver.HttpServer
import dev.aalhdydy550.tunnel.core.data.net.EndpointProbe
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [EndpointProbe] against a local HTTP server: the
 * fastest 204 wins, a 500 is ignored, and all-failing returns null.
 *
 * Note: the server binds IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI too.
 */
class EndpointProbeTest {

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Test
    fun `selectFastest picks the fastest 204 endpoint`() = runTest {
        val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
        server.createContext("/fast") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/slow") { exchange ->
            Thread.sleep(300)
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.createContext("/bad") { exchange ->
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val port = server.address.port
            val endpoints = listOf(
                EndpointProbe.ProbeEndpoint("slow", "http://[::1]:$port/slow"),
                EndpointProbe.ProbeEndpoint("bad", "http://[::1]:$port/bad"),
                EndpointProbe.ProbeEndpoint("fast", "http://[::1]:$port/fast"),
            )
            val fastest = EndpointProbe.selectFastest(client(), endpoints)
            assertEquals("fast", fastest?.name)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `selectFastest returns null when nothing answers`() = runTest {
        val closedPort = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("::1")).use { it.localPort }
        val endpoints = listOf(
            EndpointProbe.ProbeEndpoint("dead", "http://[::1]:$closedPort/x"),
        )
        assertNull(EndpointProbe.selectFastest(client(), endpoints))
    }

    @Test
    fun `default endpoints are the three public generate_204 hosts`() {
        val urls = EndpointProbe.DEFAULT_ENDPOINTS.map { it.url }
        assertEquals(
            listOf(
                "https://www.gstatic.com/generate_204",
                "https://www.google.com/generate_204",
                "https://cp.cloudflare.com/generate_204",
            ),
            urls,
        )
    }
}
