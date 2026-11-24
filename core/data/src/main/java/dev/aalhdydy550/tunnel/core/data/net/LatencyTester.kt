package dev.aalhdydy550.tunnel.core.data.net

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
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
 * UDP-only transports ([isUdpOnly]) are never probed: there is no TCP
 * listener on their port at all, so a TCP connect could never answer
 * — a WireGuard server on UDP 51820 would always look "unreachable"
 * even when it is perfectly alive. Such profiles simply have no
 * latency entry, and the UI shows no latency suffix for them.
 *
 * Pure Kotlin (java.net only): unit-tested on the JVM against a local
 * [java.net.ServerSocket].
 */
object LatencyTester {

    const val DEFAULT_TIMEOUT_MS = 3000

    /**
     * True for transports that speak UDP only — no TCP listener
     * exists on their port, so a TCP probe can never answer for
     * them. Verified against the full [ProfileType] list: VLESS,
     * VMESS, Trojan, Shadowsocks and SSH are TCP transports (their
     * probe port is a real TCP listener), and SINGBOX_JSON is a raw
     * document whose endpoint is unknown (it keeps the TCP probe).
     */
    fun isUdpOnly(type: ProfileType): Boolean = when (type) {
        ProfileType.WIREGUARD,
        ProfileType.HYSTERIA2,
        ProfileType.TUIC,
        ProfileType.ZIVPN,
        // OpenVPN: the protocol differs per profile (TCP or UDP), so a
        // TCP probe would lie about UDP servers — OpenVPN profiles are
        // never TCP-probed (same rule as WireGuard).
        ProfileType.OPENVPN -> true
        ProfileType.VLESS,
        ProfileType.VMESS,
        ProfileType.TROJAN,
        ProfileType.SHADOWSOCKS,
        ProfileType.SSH,
        ProfileType.SINGBOX_JSON -> false
    }

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

    /**
     * Probes every TCP-probeable profile concurrently. The result
     * keeps input order. UDP-only profiles ([isUdpOnly]) are skipped
     * entirely — they have no result entry, because they were never
     * tested (a missing entry is not "unreachable").
     */
    suspend fun testAll(
        profiles: List<Profile>,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): List<LatencyResult> = coroutineScope {
        profiles.filter { !isUdpOnly(it.type) }.map { profile ->
            async {
                LatencyResult(profile, tcpPing(profile.server, profile.port, timeoutMs))
            }
        }.awaitAll()
    }

    data class LatencyResult(val profile: Profile, val latencyMs: Long?)
}
