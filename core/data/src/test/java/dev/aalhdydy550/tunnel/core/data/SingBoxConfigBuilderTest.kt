package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real unit tests for [SingBoxConfigBuilder]. Pure JVM tests. */
class SingBoxConfigBuilderTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vlessProfile(): Profile = ProfileLinkParser.parseLink(
        "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&path=%2Fws#Test",
    )

    @Test
    fun `config is valid json with tun inbound and global route`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile())
        val root = json.parseToJsonElement(config).jsonObject

        val inbounds = root["inbounds"]!!.jsonArray
        // TUN first, then the local mixed proxy inbound (Hamo settings:
        // default 127.0.0.1:10808). Was 1 before the mixed inbound.
        assertEquals(2, inbounds.size)
        val mixed = inbounds[1].jsonObject
        assertEquals("mixed", mixed["type"]!!.jsonPrimitive.content)
        assertEquals("mixed-in", mixed["tag"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", mixed["listen"]!!.jsonPrimitive.content)
        assertEquals(10808, mixed["listen_port"]!!.jsonPrimitive.content.toInt())
        val tun = inbounds[0].jsonObject
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals(true, tun["auto_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, tun["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)

        val route = root["route"]!!.jsonObject
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertEquals(true, route["auto_detect_interface"]!!.jsonPrimitive.content.toBoolean())
        val rules = route["rules"]!!.jsonArray
        assertTrue(rules.any { it.jsonObject["outbound"]?.jsonPrimitive?.content == "dns-out" })
        assertTrue(rules.any { it.jsonObject["outbound"]?.jsonPrimitive?.content == "direct" })

        val outbounds = root["outbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals("proxy", outbounds[0]["tag"]!!.jsonPrimitive.content)
        assertEquals("vless", outbounds[0]["type"]!!.jsonPrimitive.content)
        assertEquals("203.0.113.10", outbounds[0]["server"]!!.jsonPrimitive.content)
        assertTrue(outbounds.any { it["type"]!!.jsonPrimitive.content == "direct" })
        assertTrue(outbounds.any { it["type"]!!.jsonPrimitive.content == "block" })
        assertTrue(outbounds.any { it["type"]!!.jsonPrimitive.content == "dns" })

        val dns = root["dns"]!!.jsonObject
        assertEquals("remote", dns["final"]!!.jsonPrimitive.content)
    }

    @Test
    fun `autoRoute false is honored`() {
        val config = SingBoxConfigBuilder.buildConfig(vlessProfile(), autoRoute = false, strictRoute = false)
        val tun = json.parseToJsonElement(config).jsonObject["inbounds"]!!.jsonArray[0].jsonObject
        assertEquals(false, tun["auto_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(false, tun["strict_route"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `singbox json profile is returned as-is`() {
        val raw = """{"log":{"level":"info"},"outbounds":[{"type":"direct","tag":"proxy"}]}"""
        val profile = Profile(
            name = "raw",
            type = ProfileType.SINGBOX_JSON,
            server = "",
            port = 0,
            outboundJson = raw,
        )
        assertEquals(raw, SingBoxConfigBuilder.buildConfig(profile))
    }

    @Test
    fun `zivpn profile builds config with local socks outbound`() {
        val clientJson = """{"server":"203.0.113.20","password":"secret","socks_port":10808}"""
        val profile = Profile(
            name = "ZIVPN test",
            type = ProfileType.ZIVPN,
            server = "203.0.113.20",
            port = 443,
            outboundJson = clientJson,
        )
        val config = SingBoxConfigBuilder.buildConfig(profile)
        val outbounds = json.parseToJsonElement(config).jsonObject["outbounds"]!!.jsonArray
        val proxy = outbounds[0].jsonObject
        assertEquals("socks", proxy["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", proxy["server"]!!.jsonPrimitive.content)
        assertEquals(10808, proxy["server_port"]!!.jsonPrimitive.content.toInt())
        assertEquals("proxy", proxy["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `zivpn socks port defaults to 1080 when absent or invalid`() {
        assertEquals(1080, SingBoxConfigBuilder.zivpnSocksPort("""{"server":"x"}"""))
        assertEquals(1080, SingBoxConfigBuilder.zivpnSocksPort("not-json"))
        assertEquals(10809, SingBoxConfigBuilder.zivpnSocksPort("""{"local_port":10809}"""))
        assertEquals(10810, SingBoxConfigBuilder.zivpnSocksPort("""{"socks5_port":10810}"""))
    }

    @Test
    fun `every supported link type builds a config`() {
        val links = listOf(
            "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443",
            "trojan://password123@203.0.113.12:443",
            "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQxMjM=@203.0.113.13:8388",
            "hysteria2://secretpass@203.0.113.14:443",
            "tuic://550e8400-e29b-41d4-a716-446655440002:tuicpass@203.0.113.15:443",
        )
        for (link in links) {
            val profile = ProfileLinkParser.parseLink(link)
            val config = SingBoxConfigBuilder.buildConfig(profile)
            val root = json.parseToJsonElement(config).jsonObject
            val outbounds = root["outbounds"]!!.jsonArray
            assertEquals("proxy", outbounds[0].jsonObject["tag"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `ads rule is emitted only when the rule-set file exists`() {
        val dir = java.nio.file.Files.createTempDirectory("rule-sets").toFile()
        try {
            // Missing file: safe fallback — no rule_set, valid config.
            val without = SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                adsBlocking = true,
                ruleSetsDir = dir.absolutePath,
            )
            val rootWithout = json.parseToJsonElement(without).jsonObject
            assertTrue("rule_set" !in rootWithout["route"]!!.jsonObject)
            // Present file: the local rule-set + block rule appear.
            java.io.File(
                dir,
                dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog.ADS_RULE_SET_FILE,
            ).writeBytes(byteArrayOf(1, 2, 3))
            val with = SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                adsBlocking = true,
                ruleSetsDir = dir.absolutePath,
            )
            val route = json.parseToJsonElement(with).jsonObject["route"]!!.jsonObject
            val ruleSet = route["rule_set"]!!.jsonArray[0].jsonObject
            assertEquals("local", ruleSet["type"]!!.jsonPrimitive.content)
            assertEquals("binary", ruleSet["format"]!!.jsonPrimitive.content)
            assertEquals(
                "geosite-category-ads-all",
                ruleSet["tag"]!!.jsonPrimitive.content,
            )
            val rules = route["rules"]!!.jsonArray
            val adsRule = rules[0].jsonObject
            assertEquals("block", adsRule["outbound"]!!.jsonPrimitive.content)
            assertEquals(
                "geosite-category-ads-all",
                adsRule["rule_set"]!!.jsonArray[0].jsonPrimitive.content,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `ads blocking off never emits the rule even when the file exists`() {
        val dir = java.nio.file.Files.createTempDirectory("rule-sets").toFile()
        try {
            java.io.File(
                dir,
                dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog.ADS_RULE_SET_FILE,
            ).writeBytes(byteArrayOf(1, 2, 3))
            val config = SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                adsBlocking = false,
                ruleSetsDir = dir.absolutePath,
            )
            val route = json.parseToJsonElement(config).jsonObject["route"]!!.jsonObject
            assertTrue("rule_set" !in route)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `dns server parameter changes the remote dns address`() {
        val config = SingBoxConfigBuilder.buildConfig(
            vlessProfile(),
            dnsServer = "tls://223.5.5.5",
        )
        val servers = json.parseToJsonElement(config)
            .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        val remote = servers.first {
            it.jsonObject["tag"]!!.jsonPrimitive.content == "remote"
        }.jsonObject
        assertEquals("tls://223.5.5.5", remote["address"]!!.jsonPrimitive.content)
        val defaultConfig = SingBoxConfigBuilder.buildConfig(vlessProfile())
        val defaultServers = json.parseToJsonElement(defaultConfig)
            .jsonObject["dns"]!!.jsonObject["servers"]!!.jsonArray
        val defaultRemote = defaultServers.first {
            it.jsonObject["tag"]!!.jsonPrimitive.content == "remote"
        }.jsonObject
        assertEquals("tls://1.1.1.1", defaultRemote["address"]!!.jsonPrimitive.content)
    }
}
