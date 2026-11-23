package dev.aalhdydy550.tunnel.core.data.net

import dev.aalhdydy550.tunnel.core.data.model.Profile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Latency probe: a plain TCP connect to the profile's server (`tcping`,
 * the same idea as the original app's `testAllTcping`).
 *
 * This measures reachability + handshake time to the server itself, NOT
 * through the tunnel — it is how the user picks the fastest server
 * *before* connecting. The live speed on screen comes from the
 * heartbeat in `feature:connection`.
 *
 * Pure Kotlin (java.net only): unit-tested on the JVM against a local
 * [java.net.ServerSocket].
 */
object LatencyTester {

    const val DEFAULT_TIMEOUT_MS = 3000

    /** TCP connect time in milliseconds, or `null` when unreachable. */
    fun tcpPing(host: String, port: Int, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }

    /** Probes every profile concurrently. The result keeps input order. */
    suspend fun testAll(
        profiles: List<Profile>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): List<LatencyResult> = coroutineScope {
        profiles.map { profile ->
            async {
                LatencyResult(profile, tcpPing(profile.server, profile.port, timeoutMs))
            }
        }.awaitAll()
    }

    data class LatencyResult(val profile: Profile, val latencyMs: Long?)
}
