package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataManager
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Real unit tests for [RoutingDataManager] against a local HTTP server:
 * a successful download lands the file and reports READY; an
 * unreachable server reports FAILED without breaking anything.
 *
 * Note: the server binds IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI too.
 */
class RoutingDataManagerTest {

    @Test
    fun `download from a local server stores the file and reports ready`() = runTest {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val server = FakeHttpServer()
        server.route("/ads.srs", 200, payload)
        server.start()
        val dir = java.nio.file.Files.createTempDirectory("rule-sets").toFile()
        try {
            val manager = RoutingDataManager(
                dir = dir,
                adsUrl = "http://[::1]:${server.port}/ads.srs",
            )
            assertEquals(RoutingDataState.NOT_DOWNLOADED, manager.status().state)
            val after = manager.ensureDownloaded()
            assertEquals(RoutingDataState.READY, after.state)
            val file = File(dir, RoutingDataCatalog.ADS_RULE_SET_FILE)
            assertTrue(file.exists())
            assertEquals(payload.size.toLong(), file.length())
        } finally {
            server.stop()
            dir.deleteRecursively()
        }
    }

    @Test
    fun `unreachable server reports failed and leaves no file`() = runTest {
        val closedPort = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("::1")).use { it.localPort }
        val dir = java.nio.file.Files.createTempDirectory("rule-sets").toFile()
        try {
            val manager = RoutingDataManager(
                dir = dir,
                adsUrl = "http://[::1]:$closedPort/ads.srs",
            )
            val after = manager.ensureDownloaded()
            assertEquals(RoutingDataState.FAILED, after.state)
            assertEquals(
                RoutingDataState.NOT_DOWNLOADED,
                manager.status().state,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
