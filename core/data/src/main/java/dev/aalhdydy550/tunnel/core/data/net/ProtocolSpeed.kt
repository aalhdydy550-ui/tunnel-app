package dev.aalhdydy550.tunnel.core.data.net

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType

/**
 * Protocol speed preference for the "fastest server" picker.
 *
 * When two servers answer with (nearly) equal latency, the faster
 * protocol wins. The order reflects the transport, not a benchmark:
 * QUIC-based transports (Hysteria2, TUIC) and the in-kernel WireGuard
 * data path beat TCP-based TLS proxies, which in turn beat the older
 * Shadowsocks/SSH transports. This is a tie-breaker only — measured
 * latency always decides first (see [pickFastest]).
 *
 * No speed numbers are claimed here: this is a preference order, and the
 * real throughput is what [SpeedTester] measures on the device.
 */
object ProtocolSpeed {

    /** Lower is faster. */
    fun speedRank(type: ProfileType): Int = when (type) {
        ProfileType.HYSTERIA2 -> 0
        ProfileType.WIREGUARD -> 1
        ProfileType.TUIC -> 2
        ProfileType.VLESS -> 3
        ProfileType.VMESS -> 4
        ProfileType.TROJAN -> 5
        ProfileType.SHADOWSOCKS -> 6
        ProfileType.SSH -> 7
        ProfileType.SINGBOX_JSON -> 8
        ProfileType.ZIVPN -> 9
    }

    /**
     * The fastest reachable profile: lowest latency first, then the
     * protocol rank. Returns `null` when nothing answered.
     */
    fun pickFastest(results: List<LatencyTester.LatencyResult>): Profile? {
        return results
            .filter { it.latencyMs != null }
            .minWithOrNull(
                compareBy(
                    { it.latencyMs },
                    { speedRank(it.profile.type) },
                ),
            )
            ?.profile
    }
}
