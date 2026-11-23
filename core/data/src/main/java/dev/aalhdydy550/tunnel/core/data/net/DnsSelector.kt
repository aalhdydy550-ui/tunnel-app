package dev.aalhdydy550.tunnel.core.data.net

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/**
 * DNS resolver selection: the fastest public resolver wins, with a
 * fixed fallback.
 *
 * The original app's default DNS was `1.1.1.1` / `223.5.5.5`
 * (plus `dns.google` / `one.one.one.one`). This app keeps those exact
 * public resolvers — and instead of hard-coding one, it measures them
 * at connect time (TCP to the DoT port 853, concurrently) and uses the
 * fastest that answers. When none answers, the config falls back to
 * `tls://1.1.1.1` — the tunnel still starts; DNS never blocks a
 * connection.
 *
 * The probe is a TCP connect only (no DNS query is sent): it measures
 * reachability + handshake time to the resolver, which is what decides
 * whether the resolver is usable from this network right now.
 *
 * Pure Kotlin (java.net only): unit-tested against local server sockets.
 */
object DnsSelector {

    data class Resolver(
        val name: String,
        /** Host used for the probe (IP or hostname). */
        val probeHost: String,
        /** DoT port used for the probe. */
        val probePort: Int = 853,
        /** The sing-box DNS server address written into the config. */
        val dotAddress: String,
    )

    val CLOUDFLARE = Resolver(
        name = "Cloudflare",
        probeHost = "1.1.1.1",
        dotAddress = "tls://1.1.1.1",
    )
    val ALIDNS = Resolver(
        name = "AliDNS",
        probeHost = "223.5.5.5",
        dotAddress = "tls://223.5.5.5",
    )
    val GOOGLE = Resolver(
        name = "Google",
        probeHost = "dns.google",
        dotAddress = "tls://dns.google",
    )

    val CANDIDATES = listOf(CLOUDFLARE, ALIDNS, GOOGLE)

    /** The fallback when no resolver answers. */
    const val FALLBACK_DOT_ADDRESS = "tls://1.1.1.1"

    /** TCP connect time to the resolver's DoT port, or null. */
    fun probe(resolver: Resolver, timeoutMs: Int = 1_500): Long? {
        val start = System.nanoTime()
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(resolver.probeHost, resolver.probePort), timeoutMs)
            }
            (System.nanoTime() - start) / 1_000_000
        } catch (e: Exception) {
            null
        }
    }

    /** Probes all candidates concurrently; the fastest answer wins. */
    suspend fun selectFastest(
        candidates: List<Resolver> = CANDIDATES,
        timeoutMs: Int = 1_500,
    ): Resolver? = coroutineScope {
        candidates.map { resolver ->
            async { resolver to probe(resolver, timeoutMs) }
        }.awaitAll()
            .filter { it.second != null }
            .minByOrNull { it.second!! }
            ?.first
    }
}
