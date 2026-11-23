package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.net.LatencyTester
import dev.aalhdydy550.tunnel.core.data.net.ProtocolSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the fastest-server picker. */
class ProtocolSpeedTest {

    private fun profile(name: String, type: ProfileType) = Profile(
        name = name,
        type = type,
        server = "203.0.113.1",
        port = 443,
        outboundJson = "{}",
    )

    @Test
    fun `lowest latency wins`() {
        val results = listOf(
            LatencyTester.LatencyResult(profile("slow-hy2", ProfileType.HYSTERIA2), 300),
            LatencyTester.LatencyResult(profile("fast-vless", ProfileType.VLESS), 40),
            LatencyTester.LatencyResult(profile("mid-wg", ProfileType.WIREGUARD), 120),
        )
        assertEquals("fast-vless", ProtocolSpeed.pickFastest(results)?.name)
    }

    @Test
    fun `equal latency prefers the faster protocol`() {
        val results = listOf(
            LatencyTester.LatencyResult(profile("vless", ProfileType.VLESS), 50),
            LatencyTester.LatencyResult(profile("hy2", ProfileType.HYSTERIA2), 50),
            LatencyTester.LatencyResult(profile("wg", ProfileType.WIREGUARD), 50),
        )
        assertEquals("hy2", ProtocolSpeed.pickFastest(results)?.name)
    }

    @Test
    fun `unreachable profiles are ignored`() {
        val results = listOf(
            LatencyTester.LatencyResult(profile("dead", ProfileType.HYSTERIA2), null),
            LatencyTester.LatencyResult(profile("alive", ProfileType.TROJAN), 90),
        )
        assertEquals("alive", ProtocolSpeed.pickFastest(results)?.name)
    }

    @Test
    fun `all unreachable returns null`() {
        val results = listOf(
            LatencyTester.LatencyResult(profile("dead1", ProfileType.HYSTERIA2), null),
            LatencyTester.LatencyResult(profile("dead2", ProfileType.VLESS), null),
        )
        assertNull(ProtocolSpeed.pickFastest(results))
    }

    @Test
    fun `hysteria2 ranks before wireguard before vless`() {
        assertEquals(0, ProtocolSpeed.speedRank(ProfileType.HYSTERIA2))
        assertEquals(1, ProtocolSpeed.speedRank(ProfileType.WIREGUARD))
        assertEquals(3, ProtocolSpeed.speedRank(ProfileType.VLESS))
    }
}
