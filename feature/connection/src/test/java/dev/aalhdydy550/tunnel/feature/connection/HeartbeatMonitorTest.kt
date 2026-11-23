package dev.aalhdydy550.tunnel.feature.connection

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [HeartbeatMonitor] against a local HTTP server:
 * a 204 answers healthy, a dead server fails, and two consecutive
 * failures fire the unhealthy callback.
 *
 * Note: the server binds IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI too.
 */
class HeartbeatMonitorTest {

    @Test
    fun `probeOnce returns true for 204`() = runTest {
        val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
        server.createContext("/generate_204") { exchange ->
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        server.start()
        try {
            val monitor = HeartbeatMonitor(
                scope = this,
                url = "http://[::1]:${server.address.port}/generate_204",
            )
            assertTrue(monitor.probeOnce())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `probeOnce returns false when the server is down`() = runTest {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        val monitor = HeartbeatMonitor(
            scope = this,
            url = "http://[::1]:$closedPort/generate_204",
        )
        assertFalse(monitor.probeOnce())
    }

    @Test
    fun `two consecutive failures fire onUnhealthy`() {
        val latch = CountDownLatch(1)
        var reason: String? = null
        val scope = CoroutineScope(Dispatchers.Default)
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1")).use { it.localPort }
        val monitor = HeartbeatMonitor(
            scope = scope,
            url = "http://[::1]:$closedPort/generate_204",
            // No endpoints to select from: start() would otherwise replace
            // the dead test URL with the fastest live public endpoint and
            // the monitor would never see a failure. Endpoint selection
            // itself is covered by EndpointProbeTest.
            endpoints = emptyList(),
            intervalMs = 100,
            maxConsecutiveFailures = 2,
            onUnhealthy = {
                reason = it
                latch.countDown()
            },
        )
        monitor.start()
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertTrue(reason!!.contains("2 times"))
        } finally {
            monitor.stop()
            scope.cancel()
        }
    }
}
