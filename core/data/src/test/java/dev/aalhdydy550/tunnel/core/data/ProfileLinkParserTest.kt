package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.Backend
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real unit tests for [ProfileLinkParser] — every scheme the app imports.
 * Pure JVM tests (no Android, no Robolectric needed).
 */
class ProfileLinkParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun outbound(outboundJson: String) =
        json.parseToJsonElement(outboundJson).jsonObject

    @Test
    fun `vless link with tls ws and fingerprint parses`() {
        val profile = ProfileLinkParser.parseLink(
            "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
                "?encryption=none&security=tls&sni=example.com&fp=chrome" +
                "&type=ws&path=%2Fws&host=example.com#Test%20VLESS",
        )
        assertEquals(ProfileType.VLESS, profile.type)
        assertEquals("Test VLESS", profile.name)
        assertEquals("203.0.113.10", profile.server)
        assertEquals(443, profile.port)
        val out = outbound(profile.outboundJson)
        assertEquals("vless", out["type"]!!.jsonPrimitive.content)
        assertEquals("550e8400-e29b-41d4-a716-446655440000", out["uuid"]!!.jsonPrimitive.content)
        val tls = out["tls"]!!.jsonObject
        assertEquals("example.com", tls["server_name"]!!.jsonPrimitive.content)
        assertEquals("chrome", tls["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        val transport = out["transport"]!!.jsonObject
        assertEquals("ws", transport["type"]!!.jsonPrimitive.content)
        assertEquals("/ws", transport["path"]!!.jsonPrimitive.content)
        assertEquals("example.com", transport["headers"]!!.jsonObject["Host"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless plain tcp link parses without tls or transport`() {
        val profile = ProfileLinkParser.parseLink(
            "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:80",
        )
        assertEquals("203.0.113.10:80", profile.name)
        val out = outbound(profile.outboundJson)
        assertTrue("tls" !in out)
        assertTrue("transport" !in out)
    }

    @Test
    fun `vmess base64 json parses`() {
        val profile = ProfileLinkParser.parseLink(
            "vmess://eyJ2IjoiMiIsInBzIjoiVGVzdCBWTWVzcyIsImFkZCI6IjIwMy4wLjExMy4xMSIsInBvcnQiOiI0NDMiLCJpZCI6IjU1MGU4NDAwLWUyOWItNDFkNC1hNzE2LTQ0NjY1NTQ0MDAwMSIsImFpZCI6IjAiLCJzY3kiOiJhdXRvIiwibmV0Ijoid3MiLCJ0eXBlIjoibm9uZSIsImhvc3QiOiJleGFtcGxlLmNvbSIsInBhdGgiOiIvdm1lc3MiLCJ0bHMiOiJ0bHMiLCJzbmkiOiJleGFtcGxlLmNvbSIsImFscG4iOiIiLCJmcCI6IiJ9",
        )
        assertEquals(ProfileType.VMESS, profile.type)
        assertEquals("Test VMess", profile.name)
        assertEquals("203.0.113.11", profile.server)
        assertEquals(443, profile.port)
        val out = outbound(profile.outboundJson)
        assertEquals("vmess", out["type"]!!.jsonPrimitive.content)
        assertEquals("auto", out["security"]!!.jsonPrimitive.content)
        assertEquals(0, out["alter_id"]!!.jsonPrimitive.content.toInt())
        assertEquals("ws", out["transport"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("/vmess", out["transport"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        assertEquals("example.com", out["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan link parses with sni and alpn`() {
        val profile = ProfileLinkParser.parseLink(
            "trojan://password123@203.0.113.12:443?sni=example.com&alpn=h2%2Chttp%2F1.1#Test%20Trojan",
        )
        assertEquals(ProfileType.TROJAN, profile.type)
        assertEquals("Test Trojan", profile.name)
        val out = outbound(profile.outboundJson)
        assertEquals("password123", out["password"]!!.jsonPrimitive.content)
        assertEquals("example.com", out["tls"]!!.jsonObject["server_name"]!!.jsonPrimitive.content)
        val alpn = out["tls"]!!.jsonObject["alpn"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(alpn.contains("h2"))
        assertTrue(alpn.contains("http/1.1"))
    }

    @Test
    fun `shadowsocks base64 userinfo form parses`() {
        val profile = ProfileLinkParser.parseLink(
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQxMjM=@203.0.113.13:8388#Test%20SS",
        )
        assertEquals(ProfileType.SHADOWSOCKS, profile.type)
        assertEquals("Test SS", profile.name)
        val out = outbound(profile.outboundJson)
        assertEquals("aes-256-gcm", out["method"]!!.jsonPrimitive.content)
        assertEquals("password123", out["password"]!!.jsonPrimitive.content)
        assertEquals(8388, profile.port)
    }

    @Test
    fun `shadowsocks full base64 form parses`() {
        val profile = ProfileLinkParser.parseLink(
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQxMjNAMjAzLjAuMTEzLjEzOjgzODg=#Test%20SS2",
        )
        assertEquals(ProfileType.SHADOWSOCKS, profile.type)
        assertEquals("203.0.113.13", profile.server)
        assertEquals("aes-256-gcm", outbound(profile.outboundJson)["method"]!!.jsonPrimitive.content)
    }

    @Test
    fun `hysteria2 link parses with obfs and mbps`() {
        val profile = ProfileLinkParser.parseLink(
            "hysteria2://secretpass@203.0.113.14:443?sni=example.com&insecure=1" +
                "&obfs=salamander&obfs-password=obfspass&upmbps=100&downmbps=200#Test%20HY2",
        )
        assertEquals(ProfileType.HYSTERIA2, profile.type)
        val out = outbound(profile.outboundJson)
        assertEquals("secretpass", out["password"]!!.jsonPrimitive.content)
        assertEquals(true, out["tls"]!!.jsonObject["insecure"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("salamander", out["obfs"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals(100, out["up_mbps"]!!.jsonPrimitive.content.toInt())
        assertEquals(200, out["down_mbps"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `hy2 alias parses as hysteria2`() {
        val profile = ProfileLinkParser.parseLink("hy2://secretpass@203.0.113.14:443")
        assertEquals(ProfileType.HYSTERIA2, profile.type)
    }

    @Test
    fun `tuic link parses`() {
        val profile = ProfileLinkParser.parseLink(
            "tuic://550e8400-e29b-41d4-a716-446655440002:tuicpass@203.0.113.15:443" +
                "?congestion_control=bbr&alpn=h3&sni=example.com#Test%20TUIC",
        )
        assertEquals(ProfileType.TUIC, profile.type)
        val out = outbound(profile.outboundJson)
        assertEquals("550e8400-e29b-41d4-a716-446655440002", out["uuid"]!!.jsonPrimitive.content)
        assertEquals("tuicpass", out["password"]!!.jsonPrimitive.content)
        assertEquals("bbr", out["congestion_control"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parseText collects good links and reports bad lines`() {
        val result = ProfileLinkParser.parseText(
            """
            vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443
            not-a-link-at-all
            trojan://password123@203.0.113.12:443#T
            """.trimIndent(),
        )
        assertEquals(2, result.profiles.size)
        assertEquals(1, result.errors.size)
        assertTrue(result.errors[0].contains("not-a-link-at-all"))
    }

    @Test
    fun `unknown scheme throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileLinkParser.parseLink("http://example.com")
        }
    }

    @Test
    fun `link without port throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileLinkParser.parseLink("vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10")
        }
    }

    @Test
    fun `ipv6 host parses`() {
        val profile = ProfileLinkParser.parseLink(
            "vless://550e8400-e29b-41d4-a716-446655440000@[2001:db8::1]:443",
        )
        assertEquals("2001:db8::1", profile.server)
        assertEquals(443, profile.port)
    }

    @Test
    fun `zivpn link is rejected with phase 2 message`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            ProfileLinkParser.parseLink("zivpn://secret@203.0.113.20:443")
        }
        assertTrue(error.message!!.contains("Phase 2"))
    }

    @Test
    fun `profile backend is external process for zivpn and sing-box otherwise`() {
        val zivpn = Profile(
            name = "ZIVPN test",
            type = ProfileType.ZIVPN,
            server = "203.0.113.20",
            port = 443,
            outboundJson = "{}",
        )
        assertEquals(Backend.EXTERNAL_PROCESS, zivpn.backend)
        val vless = ProfileLinkParser.parseLink(
            "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443",
        )
        assertEquals(Backend.SING_BOX, vless.backend)
    }

    @Test
    fun `ssh link with password parses to ssh outbound`() {
        val profile = ProfileLinkParser.parseLink(
            "ssh://root:s3cret@203.0.113.30:22#My SSH",
        )
        assertEquals(ProfileType.SSH, profile.type)
        assertEquals("My SSH", profile.name)
        assertEquals("203.0.113.30", profile.server)
        assertEquals(22, profile.port)
        val out = outbound(profile.outboundJson)
        assertEquals("ssh", out["type"]!!.jsonPrimitive.content)
        assertEquals("root", out["user"]!!.jsonPrimitive.content)
        assertEquals("s3cret", out["password"]!!.jsonPrimitive.content)
        assertEquals(Backend.SING_BOX, profile.backend)
    }

    @Test
    fun `ssh link without user is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProfileLinkParser.parseLink("ssh://203.0.113.30:22")
        }
    }

    @Test
    fun `hysteria2 ports param enables server_ports and drops server_port`() {
        val profile = ProfileLinkParser.parseLink(
            "hysteria2://secretpass@203.0.113.14:443" +
                "?sni=example.com&ports=20000-20010,30000&hop-interval=45s#HY2 hop",
        )
        val out = outbound(profile.outboundJson)
        assertTrue("server_port" !in out)
        val ports = out["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("20000-20010", "30000"), ports)
        assertEquals("45s", out["hop_interval"]!!.jsonPrimitive.content)
    }

    @Test
    fun `vless mux param adds multiplex object`() {
        val profile = ProfileLinkParser.parseLink(
            "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
                "?security=tls&mux=1#Mux",
        )
        val out = outbound(profile.outboundJson)
        val mux = out["multiplex"]!!.jsonObject
        assertEquals(true, mux["enabled"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("h2mux", mux["protocol"]!!.jsonPrimitive.content)
    }

    @Test
    fun `trojan fragment param adds tls fragment`() {
        val profile = ProfileLinkParser.parseLink(
            "trojan://secretpass@203.0.113.12:443?fragment=1#Frag",
        )
        val out = outbound(profile.outboundJson)
        assertEquals(true, out["tls"]!!.jsonObject["fragment"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `vless without mux has no multiplex object`() {
        val profile = ProfileLinkParser.parseLink(
            "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443",
        )
        assertTrue("multiplex" !in outbound(profile.outboundJson))
    }
}
