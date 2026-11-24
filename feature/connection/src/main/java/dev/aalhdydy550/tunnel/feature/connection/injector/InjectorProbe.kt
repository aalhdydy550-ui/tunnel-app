package dev.aalhdydy550.tunnel.feature.connection.injector

import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks that traffic actually flows through the tunnel after a
 * connection: a plain GET to a `generate_204` endpoint.
 *
 * Returns true ONLY on HTTP 204. Any other status, a redirect, a
 * timeout, or any I/O failure returns false. No new dependency:
 * [HttpURLConnection] only. The request is a normal app socket, so
 * while the VPN is up it travels through the TUN interface — exactly
 * the path user traffic takes.
 *
 * [url] is a parameter (instead of a hardcoded constant) so the logic
 * is unit-testable against a local stub server; production callers use
 * the default Google `generate_204` URL.
 */
class InjectorProbe {

    fun probe(
        url: String = DEFAULT_URL,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                // A redirect is not a 204: do not follow it, fail instead.
                instanceFollowRedirects = false
            }
            connection.connect()
            connection.responseCode == 204
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val DEFAULT_URL = "https://www.gstatic.com/generate_204"
        const val DEFAULT_TIMEOUT_MS = 8_000
    }
}
