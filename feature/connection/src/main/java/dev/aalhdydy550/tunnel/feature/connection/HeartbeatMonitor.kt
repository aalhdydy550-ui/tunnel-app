package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.net.EndpointProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.jvm.Volatile
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Keep-alive / heartbeat through the tunnel.
 *
 * Every [intervalMs] a tiny request goes to a `generate_204` endpoint
 * — the same kind of endpoint the original app uses for its latency
 * test. Which endpoint is picked at start by [EndpointProbe]: the
 * fastest of Google (gstatic), Google (main), and Cloudflare that
 * answers; when none answers, the default Google gstatic URL is used.
 * A 204 (or 200) means the tunnel is alive; anything else (timeout,
 * connection error, unexpected status) counts as a failure. After
 * [maxConsecutiveFailures] consecutive failures the tunnel is treated
 * as silently dead and [onUnhealthy] fires, so the service can
 * reconnect *before* the user notices a stalled page.
 *
 * The request is a normal app socket: it is NOT protected from the VPN,
 * so it travels through the TUN interface and the proxy — exactly the
 * path user traffic takes. While the tunnel is down the request fails
 * fast (it has nowhere to go), which is the point.
 */
class HeartbeatMonitor(
    private val scope: CoroutineScope,
    private val client: OkHttpClient = defaultClient(),
    private val url: String = DEFAULT_URL,
    private val endpoints: List<EndpointProbe.ProbeEndpoint> = EndpointProbe.DEFAULT_ENDPOINTS,
    private val intervalMs: Long = 60_000L,
    private val timeoutMs: Long = 10_000L,
    private val maxConsecutiveFailures: Int = 2,
    private val onHealthy: () -> Unit = {},
    private val onUnhealthy: (reason: String) -> Unit = {},
) {
    private var job: Job? = null
    private var consecutiveFailures = 0

    /** The endpoint currently used (the fastest at start, or the default). */
    @Volatile
    var currentUrl: String = url
        private set

    fun start() {
        if (job != null) return
        consecutiveFailures = 0
        job = scope.launch {
            // Best-effort: pick the fastest endpoint that answers now.
            // A failure here costs nothing — the default URL is used.
            currentUrl = runCatching {
                EndpointProbe.selectFastest(client, endpoints)?.url
            }.getOrNull() ?: url
            while (isActive) {
                delay(intervalMs)
                val ok = probeOnce()
                if (ok) {
                    consecutiveFailures = 0
                    onHealthy()
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= maxConsecutiveFailures) {
                        onUnhealthy(
                            "heartbeat failed $consecutiveFailures times in a row",
                        )
                        stop()
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        consecutiveFailures = 0
    }

    /** One probe. Returns true when the tunnel answers with 204/200. */
    suspend fun probeOnce(): Boolean = probeUrl(currentUrl)

    /** One probe against an explicit URL (used by tests). */
    suspend fun probeUrl(probeUrl: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(probeUrl).build()
            client.newCall(request).execute().use { response ->
                response.code == 204 || response.code == 200
            }
        } catch (e: Exception) {
            false
        }
    }

    companion object {
        /** Same endpoint the original app uses for its latency test. */
        const val DEFAULT_URL = "https://www.gstatic.com/generate_204"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            // 15s: a weak mobile link can take several seconds for the
            // TLS handshake; the interval is 60s, so 15s is affordable
            // and avoids false "dead tunnel" alarms.
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}
