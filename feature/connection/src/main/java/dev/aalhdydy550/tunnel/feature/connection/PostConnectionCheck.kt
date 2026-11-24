package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.net.EndpointProbe
import dev.aalhdydy550.tunnel.core.data.net.defaultOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Post-connection check (#7): after the tunnel is up, verify that
 * traffic really flows through it — instead of trusting the
 * "connected" state alone.
 *
 * Two light steps, both through the normal app socket (so they travel
 * the TUN interface, exactly like user traffic):
 *   1. a `generate_204` request, reusing [EndpointProbe] (the same
 *      endpoint family the heartbeat uses);
 *   2. one public-IP lookup from a single lightweight source
 *      ([DEFAULT_IP_URL], plain-text, one line).
 *
 * Semantics: the check succeeds when step 1 answers — that is the
 * "traffic flows" question. The public IP is display information: when
 * the IP lookup fails but traffic flows, the result is still a success
 * with [Result.publicIp] null and the detail says so, instead of
 * wrongly reporting a working tunnel as broken.
 *
 * This class does NOT decide when to run: the caller
 * ([PostConnectionCheckViewModel]) runs it only while the tunnel state
 * is [TunnelState.Started]. No background work, no periodic run — one
 * check per connection (plus a manual re-check from the card).
 */
class PostConnectionChecker(
    private val client: OkHttpClient = defaultOkHttpClient(),
    private val probeEndpoint: EndpointProbe.ProbeEndpoint = EndpointProbe.GOOGLE_STATIC,
    private val ipUrl: String = DEFAULT_IP_URL,
    private val probeTimeoutMs: Long = 10_000L,
    private val ipTimeoutMs: Long = 5_000L,
) {

    data class Result(
        val success: Boolean,
        val publicIp: String?,
        val latencyMs: Long?,
        val checkedAtMs: Long = System.currentTimeMillis(),
        val detail: String,
    )

    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val latencyMs = EndpointProbe.probe(client, probeEndpoint, probeTimeoutMs)
        if (latencyMs == null) {
            return@withContext Result(
                success = false,
                publicIp = null,
                latencyMs = null,
                detail = "تعذّر الوصول عبر النفق — تحقق من الخادم أو الشبكة",
            )
        }
        val publicIp = fetchPublicIp()
        Result(
            success = true,
            publicIp = publicIp,
            latencyMs = latencyMs,
            detail = if (publicIp != null) {
                "الترافيك يمر عبر النفق"
            } else {
                "الترافيك يمر عبر النفق — تعذّر جلب الـIP العام"
            },
        )
    }

    /** One GET to the IP service; null on any failure or junk body. */
    private fun fetchPublicIp(): String? {
        val request = Request.Builder().url(ipUrl).build()
        return try {
            client.newBuilder()
                .callTimeout(ipTimeoutMs, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return null
                    val body = response.body?.string()?.trim().orEmpty()
                    if (isValidIp(body)) body else null
                }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Plain-text IP, one line, no JSON: the lightest public-IP source. */
        const val DEFAULT_IP_URL = "https://api.ipify.org"

        /**
         * Strict enough to reject an HTML error page or a multi-line
         * body, lenient enough for IPv4 and IPv6 (including a dotted
         * tail in a v4-mapped IPv6 address).
         */
        fun isValidIp(value: String): Boolean {
            if (value.isEmpty() || value.length > 45) return false
            if (value.any { it.isWhitespace() }) return false
            // IPv4: four decimal octets, each 0–255.
            val v4 = value.split(".")
            if (v4.size == 4 &&
                v4.all { part ->
                    part.isNotEmpty() && part.all(Char::isDigit) &&
                        (part.toIntOrNull() ?: 256) <= 255
                }
            ) {
                return true
            }
            // IPv6: hex digits and colons (dots allowed for a v4 tail).
            return value.contains(":") &&
                value.all {
                    it.isDigit() || it in 'a'..'f' || it in 'A'..'F' ||
                        it == ':' || it == '.'
                }
        }
    }
}
