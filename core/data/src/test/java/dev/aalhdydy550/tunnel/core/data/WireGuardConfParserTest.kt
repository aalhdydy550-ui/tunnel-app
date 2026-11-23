package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.WireGuardConfParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Real unit tests for [WireGuardConfParser]. Pure JVM tests. */
class WireGuardConfParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val sampleConf = """
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
    """.trimIndent()

    @Test
    fun `valid conf parses to wireguard profile`() {
        val profile = WireGuardConfParser.parseConf(sampleConf)
        assertEquals(ProfileType.WIREGUARD, profile.type)
        assertEquals("203.0.113.16", profile.server)
        assertEquals(51820, profile.port)
        val out = json.parseToJsonElement(profile.outboundJson).jsonObject
        assertEquals("wireguard", out["type"]!!.jsonPrimitive.content)
        assertEquals("cGFzc3dvcmQxMjM0NTY3ODkwYWJjZGVmZ2hpamtsbW5vcA==", out["private_key"]!!.jsonPrimitive.content)
        assertEquals("cHVibGlja2V5MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3BxcnM=", out["peer_public_key"]!!.jsonPrimitive.content)
        assertEquals("cHJlc2hhcmVka2V5MTIzNDU2Nzg5MGFiY2RlZmdoaWprbG1ub3A=", out["pre_shared_key"]!!.jsonPrimitive.content)
        assertEquals(1280, out["mtu"]!!.jsonPrimitive.content.toInt())
        val addresses = out["local_address"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), addresses)
    }

    @Test
    fun `missing interface section throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parseConf("[Peer]\nPublicKey = x\nEndpoint = 1.2.3.4:51820\n")
        }
    }

    @Test
    fun `missing endpoint throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            WireGuardConfParser.parseConf(
                "[Interface]\nPrivateKey = k\nAddress = 10.0.0.2/32\n[Peer]\nPublicKey = p\n",
            )
        }
    }

    @Test
    fun `conf without preshared key or mtu parses`() {
        val profile = WireGuardConfParser.parseConf(
            """
            [Interface]
            PrivateKey = privkey
            Address = 10.0.0.2/32
            [Peer]
            PublicKey = pubkey
            Endpoint = example.com:51820
            """.trimIndent(),
        )
        val out = json.parseToJsonElement(profile.outboundJson).jsonObject
        assertEquals("example.com", profile.server)
        assertEquals("wireguard", out["type"]!!.jsonPrimitive.content)
        assertEquals(false, "pre_shared_key" in out)
        assertEquals(false, "mtu" in out)
    }
}
