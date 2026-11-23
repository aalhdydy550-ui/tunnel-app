package dev.aalhdydy550.tunnel.core.data

import com.sun.net.httpserver.HttpServer
import dev.aalhdydy550.tunnel.core.data.net.SpeedTester
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Real unit test for [SpeedTester] against a local HTTP server serving a
 * known number of bytes: the reported download size must match exactly
 * and the throughput must be positive.
 *
 * Note: the server binds IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1)
 * and no data flows there, while ::1 flows for real; ::1 works in CI too.
 */
class SpeedTesterTest {

    @Test
    fun `test downloads the full payload and reports positive throughput`() {
        val payloadSize = 512 * 1024
        val server = HttpServer.create(InetSocketAddress("::1", 0), 0)
        server.createContext("/down") { exchange ->
            val payload = ByteArray(payloadSize)
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }
        server.start()
        try {
            val url = "http://[::1]:${server.address.port}/down"
            val tester = SpeedTester(
                client = OkHttpClient.Builder()
                    .connectTimeout(5, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build(),
                downloadUrl = url,
            )
            val result = tester.test()
            assertEquals(payloadSize.toLong(), result.bytesDownloaded)
            assertTrue(result.bytesPerSecond > 0)
            assertTrue(result.elapsedMs >= 0)
        } finally {
            server.stop(0)
        }
    }
}
