package dev.aalhdydy550.tunnel.feature.connection.injector

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [InjectorConfig]: the injector document must mirror
 * the standard `SingBoxConfigBuilder` document field for field, with
 * exactly one difference — the main outbound is a local SOCKS5
 * outbound, never an `ssh` outbound, and there is no TLS block
 * anywhere (TLS lives in the JSch socket factory, not in sing-box).
 *
 * Pure JVM logic tests: no network is touched.
 */
class InjectorConfigTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun doc() = json.parseToJsonElement(InjectorConfig.buildInjectorConfig()).jsonObject

    private fun outbounds() = doc()["outbounds"]!!.jsonArray

    private fun mainOutbound() = outbounds()[0].jsonObject

    private fun tunInbound() = doc()["inbounds"]!!.jsonArray[0].jsonObject

    @Test
    fun `constants are the specified fixed values`() {
        assertEquals(10810, InjectorConstants.INJECTOR_SOCKS_PORT)
        assertEquals(15_000, InjectorConstants.SSH_CONNECT_TIMEOUT_MS)
        assertEquals(10_000, InjectorConstants.TLS_CONNECT_TIMEOUT_MS)
        assertEquals("127.0.0.1", InjectorConstants.SOCKS_BIND_ADDRESS)
    }

    @Test
    fun `main outbound is socks on 127_0_0_1 port 10810 version 5 tagged proxy`() {
        val outbound = mainOutbound()
        assertEquals("socks", outbound["type"]!!.jsonPrimitive.content)
        assertEquals("127.0.0.1", outbound["server"]!!.jsonPrimitive.content)
        assertEquals(10810, outbound["server_port"]!!.jsonPrimitive.int)
        assertEquals("5", outbound["version"]!!.jsonPrimitive.content)
        assertEquals("proxy", outbound["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no ssh outbound and no TLS block in any outbound`() {
        for (outbound in outbounds()) {
            val obj = outbound.jsonObject
            assertFalse(
                "outbound must not be ssh: $obj",
                obj["type"]!!.jsonPrimitive.content == "ssh",
            )
            assertFalse("outbound must not carry a tls block: $obj", obj.containsKey("tls"))
        }
        assertFalse(doc().containsKey("tls"))
    }

    @Test
    fun `exactly four outbounds in the standard order`() {
        val tags = outbounds().map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertEquals(listOf("proxy", "direct", "block", "dns-out"), tags)
    }

    @Test
    fun `tun inbound matches the standard builder`() {
        val tun = tunInbound()
        assertEquals("tun", tun["type"]!!.jsonPrimitive.content)
        assertEquals("tun-in", tun["tag"]!!.jsonPrimitive.content)
        assertEquals("tun0", tun["interface_name"]!!.jsonPrimitive.content)
        assertEquals("172.19.0.1/30", tun["inet4_address"]!!.jsonPrimitive.content)
        assertEquals("fdfe:dcba:9876::1/126", tun["inet6_address"]!!.jsonPrimitive.content)
        assertEquals(9000, tun["mtu"]!!.jsonPrimitive.int)
        assertTrue(tun["auto_route"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(tun["strict_route"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("gvisor", tun["stack"]!!.jsonPrimitive.content)
        assertTrue(tun["sniff"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("ipv4_only", tun["domain_strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `dns matches the standard builder`() {
        val dns = doc()["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray
        assertEquals("remote", servers[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("tls://1.1.1.1", servers[0].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["tag"]!!.jsonPrimitive.content)
        assertEquals("local", servers[1].jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("direct", servers[1].jsonObject["detour"]!!.jsonPrimitive.content)
        assertEquals("remote", dns["final"]!!.jsonPrimitive.content)
        assertEquals("ipv4_only", dns["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `route sends dns to dns-out, private ips direct, final proxy`() {
        val route = doc()["route"]!!.jsonObject
        val rules = route["rules"]!!.jsonArray
        assertEquals("dns", rules[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        assertEquals("dns-out", rules[0].jsonObject["outbound"]!!.jsonPrimitive.content)
        assertTrue(rules[1].jsonObject["ip_is_private"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("direct", rules[1].jsonObject["outbound"]!!.jsonPrimitive.content)
        assertEquals("proxy", route["final"]!!.jsonPrimitive.content)
        assertTrue(route["auto_detect_interface"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `document is deterministic across builds`() {
        assertEquals(
            InjectorConfig.buildInjectorConfig(),
            InjectorConfig.buildInjectorConfig(),
        )
    }
}
