package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.profile.WireGuardConfParser
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Hamo settings of [SingBoxConfigBuilder]: the local
 * mixed proxy inbound (`localProxyPort` / `allowOtherDevices`) and the
 * TLS-verification skip (`allowInsecure`). Pure JVM tests. */
class SingBoxConfigBuilderHamoSettingsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vlessProfile(): Profile = ProfileLinkParser.parseLink(
        "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&path=%2Fws#Test",
    )

    private fun shadowsocksProfile(): Profile = ProfileLinkParser.parseLink(
        "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQxMjM=@203.0.113.13:8388",
    )

    private fun wireGuardProfile(): Profile = WireGuardConfParser.parseConf(
        """
        [Interface]
        PrivateKey = cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW5vcA==
        Address = 10.0.0.2/32, fd00::2/128
        DNS = 1.1.1.1
        MTU = 1280

        [Peer]
        PublicKey = cHVibGlja2V5MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnM=
        PresharedKey = cHJlc2hhcmVka2V5MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3A=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = 203.0.113.16:51820
        """.trimIndent(),
    )

    private fun root(config: String) = json.parseToJsonElement(config).jsonObject

    private fun inbounds(config: String) = root(config)["inbounds"]!!.jsonArray

    private fun mixedInbound(config: String) =
        inbounds(config).first {
            it.jsonObject["tag"]?.jsonPrimitive?.content == "mixed-in"
        }.jsonObject

    private fun proxyOutbound(config: String) =
        root(config)["outbounds"]!!.jsonArray[0].jsonObject

    @Test
    fun `default config carries a mixed inbound on 127-0-0-1 port 10808`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile())
        val all = inbounds(config)
        assertEquals(2, all.size)
        // TUN stays first and untouched.
        assertEquals("tun", all[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("tun-in", all[0].jsonObject["tag"]!!.jsonPrimitive.content)
        val mixed = mixedInbound(config)
        assertEquals("mixed", mixed["type"]!!.jsonPrimitive.content)
        assertEquals("mixed-in", mixed["tag"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", mixed["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `custom local proxy port is honored`() {
        val mixed = mixedInbound(
            SingBoxConfigBuilder.buildConfig(vlessProfile(), localProxyPort = 18080),
        )
        assertEquals("127.0.0.1", mixed["listen"]!!.jsonPrimitive.content)
        assertEquals(18080, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `allowOtherDevices listens on 0-0-0-0`() {
        val mixed = mixedInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                allowOtherDevices = true,
            ),
        )
        assertEquals("0.0.0.0", mixed["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `reserved injector port 10810 falls back to 10808`() {
        val mixed = mixedInbound(
            SingBoxConfigBuilder.buildConfig(vlessProfile(), localProxyPort = 10810),
        )
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `privileged port 80 falls back to 10808`() {
        val mixed = mixedInbound(
            SingBoxConfigBuilder.buildConfig(vlessProfile(), localProxyPort = 80),
        )
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `allowInsecure true sets tls insecure on a TLS outbound`() {
        val proxy = proxyOutbound(
            SingBoxConfigBuilder.buildConfig(vlessProfile(), allowInsecure = true),
        )
        val tls = proxy["tls"]!!.jsonObject
        assertEquals(true, tls["insecure"]!!.jsonPrimitive.content.toBoolean())
        // The pre-existing TLS fields survive untouched.
        assertEquals("example.com", tls["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `allowInsecure false adds no insecure key anywhere`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile(), allowInsecure = false)
        val tls = proxyOutbound(config)["tls"]!!.jsonObject
        assertFalse("insecure" in tls)
        assertFalse(config.contains("insecure"))
    }

    @Test
    fun `allowInsecure true on an outbound without tls adds no tls key`() {
        val config = SingBoxConfigBuilder.buildConfig(
            shadowsocksProfile(),
            allowInsecure = true,
        )
        val proxy = proxyOutbound(config)
        assertNull(proxy["tls"])
        assertFalse(config.contains("insecure"))
    }

    @Test
    fun `wireguard config is unchanged by allowInsecure`() {
        val plain = SingBoxConfigBuilder.buildConfig(wireGuardProfile())
        val insecure = SingBoxConfigBuilder.buildConfig(
            wireGuardProfile(),
            allowInsecure = true,
        )
        assertEquals(plain, insecure)
        assertNull(proxyOutbound(plain)["tls"])
        assertFalse(plain.contains("insecure"))
        // The mixed inbound is still emitted for WireGuard.
        assertEquals(10808, mixedInbound(plain)["listen_port"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `singbox json profile is returned as-is with no mixed inbound`() {
        val raw = """{"log":{"level":"info"},"outbounds":[{"type":"direct","tag":"proxy"}]}"""
        val profile = Profile(
            name = "raw",
            type = ProfileType.SINGBOX_JSON,
            server = "",
            port = 0,
            outboundJson = raw,
        )
        val config = SingBoxConfigBuilder.buildConfig(
            profile,
            localProxyPort = 18080,
            allowOtherDevices = true,
            allowInsecure = true,
        )
        assertEquals(raw, config)
        assertFalse(config.contains("mixed-in"))
    }

    @Test
    fun `zivpn socks outbound gains no tls key but still gets the mixed inbound`() {
        val clientJson = """{"server":"203.0.113.20","password":"secret","socks_port":10808}"""
        val profile = Profile(
            name = "ZIVPN test",
            type = ProfileType.ZIVPN,
            server = "203.0.113.20",
            port = 443,
            outboundJson = clientJson,
        )
        val config = SingBoxConfigBuilder.buildConfig(profile, allowInsecure = true)
        val proxy = proxyOutbound(config)
        assertEquals("socks", proxy["type"]!!.jsonPrimitive.content)
        assertNull(proxy["tls"])
        assertFalse(config.contains("insecure"))
        assertEquals("mixed", mixedInbound(config)["type"]!!.jsonPrimitive.content)
    }
}
