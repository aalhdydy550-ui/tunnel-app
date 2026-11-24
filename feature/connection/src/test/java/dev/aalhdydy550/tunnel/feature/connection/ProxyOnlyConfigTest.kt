package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for proxy-only mode (no VPN) in [SingBoxConfigBuilder]:
 * with `proxyOnly = true` the TUN inbound (`tun-in`) is omitted
 * entirely and only the `mixed` inbound (`mixed-in`) remains, so the
 * engine never opens the VPN interface. Pure JVM tests; the produced
 * JSON is parsed with kotlinx-serialization, never matched as text. */
class ProxyOnlyConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vlessProfile(): Profile = ProfileLinkParser.parseLink(
        "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&path=%2Fws#Test",
    )

    private fun root(config: String): JsonObject =
        json.parseToJsonElement(config).jsonObject

    private fun inbounds(config: String) = root(config)["inbounds"]!!.jsonArray

    private fun inboundByTag(config: String, tag: String): JsonObject? =
        inbounds(config).map { it.jsonObject }
            .firstOrNull { it["tag"]?.jsonPrimitive?.content == tag }

    private fun mixedInbound(config: String): JsonObject =
        inboundByTag(config, "mixed-in")!!

    @Test
    fun `proxyOnly true emits no tun inbound and keeps mixed-in on 10808`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile(), proxyOnly = true)
        val all = inbounds(config)
        assertEquals(1, all.size)
        assertTrue(
            "no inbound of type tun may remain in proxy-only mode",
            all.none { it.jsonObject["type"]?.jsonPrimitive?.content == "tun" },
        )
        assertEquals(null, inboundByTag(config, "tun-in"))
        val mixed = mixedInbound(config)
        assertEquals("mixed", mixed["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", mixed["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `proxyOnly true keeps outbounds route and dns unchanged`() {
        val proxyOnly = SingBoxConfigBuilder.buildConfig(vlessProfile(), proxyOnly = true)
        val vpn = SingBoxConfigBuilder.buildConfig(vlessProfile(), proxyOnly = false)
        assertEquals(root(vpn)["outbounds"], root(proxyOnly)["outbounds"])
        assertEquals(root(vpn)["route"], root(proxyOnly)["route"])
        assertEquals(root(vpn)["dns"], root(proxyOnly)["dns"])
        assertEquals(root(vpn)["log"], root(proxyOnly)["log"])
        // The proxy outbound still points at the profile's server.
        val outbound = root(proxyOnly)["outbounds"]!!.jsonArray[0].jsonObject
        assertEquals("proxy", outbound["tag"]!!.jsonPrimitive.content)
        assertEquals("203.0.113.10", outbound["server"]!!.jsonPrimitive.content)
    }

    @Test
    fun `proxyOnly false keeps tun-in and mixed-in (current behaviour)`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile(), proxyOnly = false)
        val all = inbounds(config)
        assertEquals(2, all.size)
        assertEquals("tun", all[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tun-in", all[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("mixed", all[1].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("mixed-in", all[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals(10808, mixedInbound(config)["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `default proxyOnly is false and byte-identical to explicit false`() {
        val default = SingBoxConfigBuilder.buildConfig(vlessProfile())
        val explicit = SingBoxConfigBuilder.buildConfig(vlessProfile(), proxyOnly = false)
        assertEquals(explicit, default)
    }

    @Test
    fun `singbox json profile is returned as-is even with proxyOnly true`() {
        val raw = """{"log":{"level":"info"},"inbounds":[{"type":"tun","tag":"tun-in"}],"outbounds":[{"type":"direct","tag":"proxy"}]}"""
        val profile = Profile(
            name = "raw",
            type = ProfileType.SINGBOX_JSON,
            server = "",
            port = 0,
            outboundJson = raw,
        )
        // Documented limitation: proxy-only does not apply to a stored
        // document — the mode only takes effect if the document itself
        // already carries a `mixed` inbound.
        val config = SingBoxConfigBuilder.buildConfig(profile, proxyOnly = true)
        assertEquals(raw, config)
        assertTrue(config.contains("tun-in"))
        assertFalse(config.contains("mixed-in"))
    }

    @Test
    fun `proxyOnly true with allowOtherDevices listens on 0-0-0-0`() {
        val mixed = mixedInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                proxyOnly = true,
                allowOtherDevices = true,
            ),
        )
        assertEquals("0.0.0.0", mixed["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `proxyOnly true with invalid ports falls back to 10808`() {
        for (port in listOf(80, 10810, 70000, 0)) {
            val mixed = mixedInbound(
                SingBoxConfigBuilder.buildConfig(
                    vlessProfile(),
                    proxyOnly = true,
                    localProxyPort = port,
                ),
            )
            assertEquals(
                "port $port must fall back to 10808",
                10808,
                mixed["listen_port"]!!.jsonPrimitive.content.toInt(),
            )
        }
    }

    @Test
    fun `proxyOnly true with a custom valid port honors it`() {
        val mixed = mixedInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                proxyOnly = true,
                localProxyPort = 18080,
            ),
        )
        assertEquals(18080, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `proxyOnly true drops per-app lists with the tun inbound`() {
        val config = SingBoxConfigBuilder.buildConfig(
            vlessProfile(),
            proxyOnly = true,
            includePackages = listOf("com.example.app"),
            excludePackages = listOf("com.other.app"),
        )
        assertFalse(config.contains("include_package"))
        assertFalse(config.contains("exclude_package"))
        assertFalse(config.contains("com.example.app"))
        assertFalse(config.contains("com.other.app"))
        // And the same lists ARE emitted in VPN mode (control case).
        val vpnConfig = SingBoxConfigBuilder.buildConfig(
            vlessProfile(),
            proxyOnly = false,
            includePackages = listOf("com.example.app"),
        )
        assertTrue(vpnConfig.contains("include_package"))
    }
}
