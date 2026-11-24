package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.backup.importProfilesFromJson
import dev.aalhdydy550.tunnel.core.data.profile.SubscriptionFetcher
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Unit tests for [SubscriptionFetcher] against a local HTTP server.
 *
 * The server binds IPv6 loopback (::1) — on this dev VM the sandbox
 * fake-accepts Java TCP connects on IPv4 loopback (127.0.0.1) and no
 * data flows there, while ::1 flows for real; ::1 works in CI too
 * (same pattern as EndpointProbeTest).
 */
class SubscriptionFetcherTest {

    private val vlessLink =
        "vless://11111111-1111-1111-1111-111111111111@203.0.113.10:443#Germany"
    private val trojanLink = "trojan://secret@203.0.113.20:443#France"

    private fun client() = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private fun serverWith(body: String, code: Int = 200): FakeHttpServer {
        val server = FakeHttpServer()
        server.route("/sub", code, body)
        server.start()
        return server
    }

    @Test
    fun `decodeBody returns plain text unchanged`() {
        val plain = "$vlessLink\n$trojanLink"
        assertEquals(plain, SubscriptionFetcher.decodeBody(plain))
    }

    @Test
    fun `decodeBody decodes standard base64`() {
        val plain = "$vlessLink\n$trojanLink"
        val encoded = Base64.getEncoder()
            .encodeToString(plain.toByteArray(Charsets.UTF_8))
        assertEquals(plain, SubscriptionFetcher.decodeBody(encoded))
    }

    @Test
    fun `decodeBody decodes url-safe base64 without padding`() {
        val plain = "$vlessLink\n$trojanLink"
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(plain.toByteArray(Charsets.UTF_8))
        assertEquals(plain, SubscriptionFetcher.decodeBody(encoded))
    }

    @Test
    fun `decodeBody returns undecodable text unchanged`() {
        assertEquals("not-a-subscription!!!", SubscriptionFetcher.decodeBody("not-a-subscription!!!"))
        // Decodes to binary garbage (replacement chars) — not a list.
        assertEquals("////", SubscriptionFetcher.decodeBody("////"))
    }

    @Test
    fun `fetchAndParse reads a plain list from the server`() = runTest {
        val server = serverWith("$vlessLink\n$trojanLink")
        try {
            val port = server.port
            val result = SubscriptionFetcher.fetchAndParse(
                client(),
                "http://[::1]:$port/sub",
            )
            assertTrue(result.errors.isEmpty())
            assertEquals(2, result.profiles.size)
            assertEquals("Germany", result.profiles[0].name)
            assertEquals("203.0.113.10", result.profiles[0].server)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `fetchAndParse reads a base64 list from the server`() = runTest {
        val plain = "$vlessLink\n$trojanLink"
        val encoded = Base64.getEncoder()
            .encodeToString(plain.toByteArray(Charsets.UTF_8))
        val server = serverWith(encoded)
        try {
            val port = server.port
            val result = SubscriptionFetcher.fetchAndParse(
                client(),
                "http://[::1]:$port/sub",
            )
            assertTrue(result.errors.isEmpty())
            assertEquals(2, result.profiles.size)
            assertEquals("France", result.profiles[1].name)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `fetchAndParse throws on HTTP error`() = runTest {
        val server = serverWith("nope", 404)
        try {
            val port = server.port
            try {
                SubscriptionFetcher.fetchAndParse(client(), "http://[::1]:$port/sub")
                fail("expected an exception for HTTP 404")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("404"))
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `fetchAndParse rejects a non-http URL`() = runTest {
        try {
            SubscriptionFetcher.fetchAndParse(client(), "ftp://example.com/sub")
            fail("expected an exception for a non-http URL")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    @Test
    fun `fetchAndParse rejects plain http to a non-loopback host`() = runTest {
        // The network security config forbids cleartext, so a remote
        // http:// URL could never be fetched — it must fail validation,
        // not later at the platform. Loopback http stays allowed (tests).
        try {
            SubscriptionFetcher.fetchAndParse(client(), "http://example.com/sub")
            fail("expected an exception for plain http to a remote host")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("http"))
        }
    }

    /**
     * Round-trip for the new import path (the Hamo lesson: a list the
     * app cannot read back is a shipped bug): fetch → parse → export
     * → import must return the same servers.
     */
    @Test
    fun `fetch parse export import round-trips`() = runTest {
        val server = serverWith("$vlessLink\n$trojanLink")
        try {
            val port = server.port
            val fetched = SubscriptionFetcher.fetchAndParse(
                client(),
                "http://[::1]:$port/sub",
            )
            assertEquals(2, fetched.profiles.size)
            val json = exportProfilesToJson(fetched.profiles)
            val reimported = importProfilesFromJson(json)
            assertTrue(reimported.errors.isEmpty())
            assertEquals(2, reimported.profiles.size)
            assertEquals("Germany", reimported.profiles[0].name)
            assertEquals("203.0.113.10", reimported.profiles[0].server)
            assertEquals("France", reimported.profiles[1].name)
            assertEquals("203.0.113.20", reimported.profiles[1].server)
        } finally {
            server.stop()
        }
    }
}
