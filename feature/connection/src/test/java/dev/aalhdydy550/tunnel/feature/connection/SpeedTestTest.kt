package dev.aalhdydy550.tunnel.feature.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.math.abs

/**
 * Unit tests for [SpeedTest] against the local [FakeHttpServer] — no
 * live network: the `__down` endpoint is stubbed on IPv6 loopback
 * (::1), the same pattern [PostConnectionCheckTest] uses.
 *
 * Note on the harness: [runTest] is used only for the guard test
 * (it throws before any I/O); every test that touches the socket
 * uses [runBlocking] instead — `runDownload` caps itself with a real
 * 10-second [withTimeout], whose virtual clock under `runTest`
 * fires while real loopback I/O is still in flight (verified:
 * `TimeoutCancellationException: Timed out after 10s of _virtual_
 * time` on the failure/HTTP-error tests). That is a test-harness
 * artifact, not a production bug: in production the cap is
 * wall-clock, as intended.
 */
class SpeedTestTest {

    companion object {
        /** 5MB — the first adaptive round, so the fake body is one round. */
        private const val BODY_BYTES = 5 * 1024 * 1024

        private val BODY = ByteArray(BODY_BYTES) { (it % 251).toByte() }
    }

    private fun speedTestFor(server: FakeHttpServer): SpeedTest =
        SpeedTest(
            client = OkHttpClient(),
            baseUrl = "http://[::1]:${server.port}",
        )

    @Test
    fun `download measures known bytes and consistent mbps`() = runBlocking {
        val server = FakeHttpServer()
        server.route("/__down", 200, BODY)
        server.start()
        try {
            var lastProgressBytes = 0L
            var progressCalls = 0
            var previousBytes = 0L
            val result = speedTestFor(server).runDownload(true) { bytes, _, _ ->
                // Progress is monotonic and grows with every chunk.
                assertTrue(bytes >= previousBytes)
                previousBytes = bytes
                lastProgressBytes = bytes
                progressCalls++
            }
            assertTrue(progressCalls > 0)
            assertEquals(lastProgressBytes, result.bytesDownloaded)
            // Every round downloads exactly the stubbed 5MB body, so
            // the total is a whole number of bodies (1–4 rounds: the
            // adaptive loop caps at a 25MB request).
            assertTrue(result.bytesDownloaded % BODY_BYTES.toLong() == 0L)
            assertTrue(result.bytesDownloaded in BODY_BYTES.toLong()..4L * BODY_BYTES)
            // Inside the 10-second cap, and never zero (division guard).
            assertTrue(result.durationMs in 1 until SpeedTest.TIMEOUT_MS)
            // Mbps is exactly bytes / time — no invented number.
            val expectedMbps = result.bytesDownloaded * 8.0 /
                (result.durationMs / 1_000.0) / 1_000_000.0
            assertTrue(abs(result.downloadMbps - expectedMbps) / expectedMbps < 0.05)
            assertTrue(result.downloadMbps > 0.0)
            assertTrue(result.testedAt <= System.currentTimeMillis())
            assertTrue(result.testedAt > System.currentTimeMillis() - 60_000)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `cancelling mid-download stops cleanly with no result`() = runBlocking {
        val server = FakeHttpServer()
        server.route("/__down", 200, BODY)
        server.start()
        try {
            val test = speedTestFor(server)
            var job: Job? = null
            var delivered = false
            job = launch {
                try {
                    test.runDownload(true) { downloaded, _, _ ->
                        // Cancel our own download once it is really
                        // flowing — deterministic, no timing race.
                        if (downloaded >= 64 * 1024) job?.cancel()
                    }
                    delivered = true
                } catch (e: CancellationException) {
                    // Re-throw so the job ends cancelled, not normally.
                    throw e
                }
            }
            // A generous real-time bound so a stuck download fails
            // the test instead of hanging the suite.
            withTimeout(30_000) { job.join() }
            assertTrue(job.isCancelled)
            assertFalse(delivered)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `not connected refuses to start even when the server is up`() = runTest {
        val server = FakeHttpServer()
        server.route("/__down", 200, BODY)
        server.start()
        try {
            try {
                speedTestFor(server).runDownload(false) { _, _, _ -> }
                fail("expected IllegalStateException")
            } catch (e: IllegalStateException) {
                assertTrue(e.message!!.contains("بلا اتصال قائم"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `network failure throws IOException not a fake number`() = runBlocking {
        val closedPort = ServerSocket(0, 0, InetAddress.getByName("::1"))
            .use { it.localPort }
        val test = SpeedTest(
            client = OkHttpClient(),
            baseUrl = "http://[::1]:$closedPort",
        )
        try {
            test.runDownload(true) { _, _, _ -> }
            fail("expected IOException")
        } catch (e: IOException) {
            // ConnectException — the failure surfaces, no number invented.
            assertTrue(e.message != null)
        }
    }

    @Test
    fun `HTTP error throws IOException not a fake number`() = runBlocking {
        val server = FakeHttpServer()
        server.route("/__down", 500)
        server.start()
        try {
            try {
                speedTestFor(server).runDownload(true) { _, _, _ -> }
                fail("expected IOException")
            } catch (e: IOException) {
                assertTrue(e.message!!.contains("500"))
            }
        } finally {
            server.stop()
        }
    }
}
