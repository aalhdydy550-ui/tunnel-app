package dev.aalhdydy550.tunnel.core.data.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Multi-point connectivity probe.
 *
 * The original app tested latency against two public Google endpoints
 * (`www.gstatic.com/generate_204` and `www.google.com/generate_204`).
 * This app keeps both and adds a third (Cloudflare's
 * `cp.cloudflare.com/generate_204`), then uses the fastest one that
 * answers — instead of assuming one host is always the quickest.
 *
 * A `generate_204` endpoint is a public captive-portal check: it
 * returns HTTP 204 with an empty body, so a probe costs almost
 * nothing. The heartbeat uses the selected endpoint; the latency test
 * for *servers* is separate ([LatencyTester], TCP to the server).
 *
 * Pure OkHttp + coroutines: unit-tested against a local HTTP server.
 */
object EndpointProbe {

    data class ProbeEndpoint(val name: String, val url: String)

    val GOOGLE_STATIC = ProbeEndpoint(
        "Google (gstatic)",
        "https://www.gstatic.com/generate_204",
    )
    val GOOGLE_MAIN = ProbeEndpoint(
        "Google",
        "https://www.google.com/generate_204",
    )
    val CLOUDFLARE = ProbeEndpoint(
        "Cloudflare",
        "https://cp.cloudflare.com/generate_204",
    )

    val DEFAULT_ENDPOINTS = listOf(GOOGLE_STATIC, GOOGLE_MAIN, CLOUDFLARE)

    /** One probe: milliseconds to a 204/200, or null when it fails. */
    suspend fun probe(
        client: OkHttpClient,
        endpoint: ProbeEndpoint,
        // 10s, not 5s: a TLS handshake on a weak mobile link can take
        // several seconds, and a timed-out probe costs nothing — the
        // heartbeat falls back to the default endpoint when selection
        // returns null.
        timeoutMs: Long = 10_000,
    ): Long? {
        val request = Request.Builder().url(endpoint.url).build()
        val start = System.nanoTime()
        return try {
            // A per-call timeout keeps one slow endpoint from blocking
            // the selection; the client's own timeouts stay as-is.
            client.newBuilder()
                .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    if (response.code == 204 || response.code == 200) {
                        (System.nanoTime() - start) / 1_000_000
                    } else {
                        null
                    }
                }
        } catch (e: Exception) {
            null
        }
    }

    /** Probes all endpoints concurrently; the fastest answer wins. */
    suspend fun selectFastest(
        client: OkHttpClient,
        endpoints: List<ProbeEndpoint> = DEFAULT_ENDPOINTS,
    ): ProbeEndpoint? = coroutineScope {
        endpoints.map { endpoint ->
            async { endpoint to probe(client, endpoint) }
        }.awaitAll()
            .filter { it.second != null }
            .minByOrNull { it.second!! }
            ?.first
    }
}
