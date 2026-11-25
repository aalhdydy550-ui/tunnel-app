package dev.aalhdydy550.tunnel.core.data.update

import dev.aalhdydy550.tunnel.core.data.FakeHttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [GithubReleaseChecker] against the local stub
 * server (IPv6 loopback, same pattern as the other HTTP tests).
 * No live network: the checker is pointed at the stub via its
 * [UpdateChecker.checkLatest] URL parameter.
 */
class GithubReleaseCheckerTest {

    private lateinit var server: FakeHttpServer
    private lateinit var checker: GithubReleaseChecker

    private fun apiUrl(path: String = "/releases/latest"): String =
        "http://[::1]:${server.port}$path"

    @Before
    fun setUp() {
        server = FakeHttpServer()
        server.start()
        checker = GithubReleaseChecker(
            OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .build(),
        )
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private val releaseJson = """
        {
          "tag_name": "v0.2.0",
          "name": "Ayman Tunnel Plus 0.2.0",
          "published_at": "2026-11-24T10:00:00Z",
          "assets": [
            {"name": "notes.zip", "size": 1234, "browser_download_url": "http://example.invalid/n.zip"},
            {"name": "ayman-tunnel-plus-v0.2.0-3.apk", "size": 52428800, "browser_download_url": "http://example.invalid/a.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun `valid release parses tag assets and versionCode`() = runBlocking {
        server.route("/releases/latest", 200, releaseJson)
        val result = checker.checkLatest(apiUrl())
        assertTrue(result.isSuccess)
        val release = result.getOrThrow()
        assertEquals("v0.2.0", release.tag)
        assertEquals("Ayman Tunnel Plus 0.2.0", release.name)
        assertEquals("2026-11-24", release.publishedDate)
        assertEquals("ayman-tunnel-plus-v0.2.0-3.apk", release.apkAsset?.name)
        assertEquals(52_428_800L, release.apkAsset?.sizeBytes)
        assertEquals("http://example.invalid/a.apk", release.apkAsset?.downloadUrl)
        assertEquals(3L, release.versionCode)
    }

    @Test
    fun `assets without an apk yield a release with no apk`() = runBlocking {
        server.route(
            "/releases/latest",
            200,
            """{"tag_name": "v0.2.0", "assets": [{"name": "notes.zip", "size": 1, "browser_download_url": "http://x"}]}""",
        )
        val release = checker.checkLatest(apiUrl()).getOrThrow()
        assertNull(release.apkAsset)
        assertNull(release.versionCode)
    }

    @Test
    fun `assets missing a download url are skipped`() = runBlocking {
        server.route(
            "/releases/latest",
            200,
            """{"tag_name": "v0.2.0", "assets": [{"name": "broken.apk", "size": 1}, {"name": "good-2.apk", "size": 2, "browser_download_url": "http://x/g.apk"}]}""",
        )
        val release = checker.checkLatest(apiUrl()).getOrThrow()
        assertEquals("good-2.apk", release.apkAsset?.name)
        assertEquals(2L, release.versionCode)
    }

    @Test
    fun `null name in json is tolerated`() = runBlocking {
        server.route(
            "/releases/latest",
            200,
            """{"tag_name": "v0.2.0", "name": null, "assets": []}""",
        )
        val release = checker.checkLatest(apiUrl()).getOrThrow()
        assertNull(release.name)
    }

    @Test
    fun `404 becomes HttpError 404`() = runBlocking {
        server.route("/releases/latest", 404, "Not Found")
        val result = checker.checkLatest(apiUrl())
        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue(error is UpdateCheckError.HttpError)
        assertEquals(404, (error as UpdateCheckError.HttpError).code)
    }

    @Test
    fun `500 becomes HttpError 500`() = runBlocking {
        server.route("/releases/latest", 500, "boom")
        val result = checker.checkLatest(apiUrl())
        assertTrue(result.isFailure)
        assertEquals(500, (result.exceptionOrNull() as UpdateCheckError.HttpError).code)
    }

    @Test
    fun `malformed json becomes BadResponse`() = runBlocking {
        server.route("/releases/latest", 200, "{not json")
        val result = checker.checkLatest(apiUrl())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UpdateCheckError.BadResponse)
    }

    @Test
    fun `missing tag_name becomes BadResponse`() = runBlocking {
        server.route("/releases/latest", 200, """{"name": "x", "assets": []}""")
        val result = checker.checkLatest(apiUrl())
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UpdateCheckError.BadResponse)
    }

    @Test
    fun `unreachable host becomes Network`() = runBlocking {
        // A port that was just bound and closed: nothing listens there,
        // so the connect is refused at once (same trick as the other
        // HTTP tests — a fixed low port might be intercepted).
        val closedPort = java.net.ServerSocket(0, 0, java.net.InetAddress.getByName("::1"))
            .use { it.localPort }
        val result = checker.checkLatest("http://[::1]:$closedPort/releases/latest")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is UpdateCheckError.Network)
    }
}
