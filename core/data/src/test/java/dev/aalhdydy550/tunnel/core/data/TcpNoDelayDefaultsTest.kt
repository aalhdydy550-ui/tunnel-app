package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard tests for the TCP No Delay default (§4-#18).
 *
 * sing-box 1.14.4 has no `tcp_nodelay` dialer field and rejects
 * unknown config fields at startup, so the option must NOT appear
 * in the generated config — with the default (off) the config the
 * builder emits is byte-for-byte the config it emitted before this
 * change. If sing-box ever gains the field, these tests fail loudly
 * on purpose: adding it then needs a deliberate, tested decision.
 */
class TcpNoDelayDefaultsTest {

    private fun vlessProfile() = ProfileLinkParser.parseLink(
        "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&path=%2Fws#Test",
    )

    @Test
    fun `default config contains no tcp-nodelay field`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile())
        assertFalse(
            "generated config must not mention tcp_nodelay",
            config.lowercase().contains("nodelay"),
        )
    }

    @Test
    fun `default config still contains the normal dialer knobs`() {
        // Sanity: the guard above is not passing because the builder
        // stopped emitting outbounds altogether.
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile())
        assertTrue(config.contains("\"type\":\"vless\""))
        assertTrue(config.contains("\"tag\":\"proxy\""))
    }
}
