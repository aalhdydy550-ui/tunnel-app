package dev.aalhdydy550.tunnel.core.data.update

import dev.aalhdydy550.tunnel.core.data.FakeHttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory

/**
 * Unit tests for [OkHttpUpdateDownloader] against the local stub
 * server. The downloader writes to a temp dir (its `cacheDir`
 * parameter), never to a real Android cache — so no Context is
 * needed anywhere in this test.
 */
class UpdateDownloaderTest {

    private lateinit var server: FakeHttpServer
    private lateinit var cacheDir: File
    private lateinit var downloader: OkHttpUpdateDownloader

    private fun apkUrl(path: String = "/app.apk"): String = "http://[::1]:${server.port}$path"

    @Before
    fun setUp() {
        server = FakeHttpServer()
        server.start()
        cacheDir = createTempDirectory(prefix = "update-dl-test").toFile()
        downloader = OkHttpUpdateDownloader(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
            cacheDir,
        )
    }

    @After
    fun tearDown() {
        server.stop()
        cacheDir.deleteRecursively()
    }

    @Test
    fun `downloads the full file and reports monotonic progress to 100 percent`() = runBlocking {
        val body = ByteArray(100_000) { (it % 251).toByte() }
        server.route("/app.apk", 200, body)
        val progress = mutableListOf<Pair<Long, Long?>>()
        val result = downloader.download(apkUrl()) { downloaded, total ->
            progress.add(downloaded to total)
        }
        assertTrue(result.isSuccess)
        val file = result.getOrThrow()
        assertEquals(File(cacheDir, "updates/update.apk"), file)
        assertTrue(file.exists())
        assertEquals(body.toList(), file.readBytes().toList())
        assertTrue(progress.isNotEmpty())
        // Monotonic, and the last report is the full size.
        for (i in 1 until progress.size) {
            assertTrue(progress[i].first >= progress[i - 1].first)
        }
        assertEquals(100_000L to 100_000L, progress.last())
        // No temp file left behind.
        assertFalse(File(cacheDir, "updates/update.apk.tmp").exists())
    }

    @Test
    fun `http error fails and leaves no file`() = runBlocking {
        server.route("/app.apk", 404, "Not Found")
        val result = downloader.download(apkUrl()) { _, _ -> }
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UpdateCheckError.HttpError)
        assertFalse(File(cacheDir, "updates/update.apk").exists())
        assertFalse(File(cacheDir, "updates/update.apk.tmp").exists())
    }

    @Test
    fun `a stale temp file from a killed run is discarded`() = runBlocking {
        val stale = File(cacheDir, "updates/update.apk.tmp").apply {
            parentFile.mkdirs()
            writeBytes(ByteArray(10) { 7 })
        }
        assertTrue(stale.exists())
        server.route("/app.apk", 200, ByteArray(50) { 1 })
        val result = downloader.download(apkUrl()) { _, _ -> }
        assertTrue(result.isSuccess)
        assertFalse(File(cacheDir, "updates/update.apk.tmp").exists())
        assertEquals(50, result.getOrThrow().length().toInt())
    }

    @Test
    fun `cancelling mid-download aborts and deletes the partial file`() = runBlocking {
        // The stub waits 30s before answering; the download is
        // cancelled after 300ms — it must abort at once, not after
        // the read timeout, and leave no partial file.
        server.route("/app.apk", 200, ByteArray(10) { 1 }, delayMs = 30_000)
        val job = async {
            downloader.download(apkUrl()) { _, _ -> }
        }
        delay(300)
        job.cancel()
        val thrown = runCatching { job.await() }.exceptionOrNull()
        assertTrue(thrown is CancellationException)
        assertFalse(File(cacheDir, "updates/update.apk").exists())
        assertFalse(File(cacheDir, "updates/update.apk.tmp").exists())
    }
}
