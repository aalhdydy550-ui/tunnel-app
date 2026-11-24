package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.OvpnConfParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real unit tests for [OvpnConfParser] — valid files (TCP/UDP), embedded
 * blocks, multiple remotes, and every rejection rule. Pure JVM tests
 * (no Android, no Robolectric needed).
 */
class OvpnConfParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun outbound(outboundJson: String) =
        json.parseToJsonElement(outboundJson).jsonObject

    private fun assertRejected(text: String, name: String = "vpn.ovpn") {
        val e = assertThrows(IllegalArgumentException::class.java) {
            OvpnConfParser.parseConf(text, name)
        }
        assertTrue("error message must be Arabic, was: ${e.message}", e.message.orEmpty().isNotBlank())
    }

    @Test
    fun `valid tcp file parses with first remote shown`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            proto tcp
            remote vpn.example.com 443 tcp
            resolv-retry infinite
            nobind
            cipher AES-256-GCM
            auth SHA256
            remote-cert-tls server
            """.trimIndent(),
            "my-vpn.ovpn",
        )
        assertEquals(ProfileType.OPENVPN, profile.type)
        assertEquals("my-vpn", profile.name)
        assertEquals("vpn.example.com", profile.server)
        assertEquals(443, profile.port)
        val out = outbound(profile.outboundJson)
        assertEquals("openvpn", out["type"]!!.jsonPrimitive.content)
        assertEquals("tcp", out["proto"]!!.jsonPrimitive.content)
        assertEquals("TCP", OvpnConfParser.protocolLabel(profile.outboundJson))
        assertFalse(out["auth_user_pass"]!!.jsonPrimitive.boolean)
        assertFalse(out["has_embedded_certs"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `valid udp file defaults to udp and port 1194`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            remote 203.0.113.10
            compress lz4
            """.trimIndent(),
            "udp-vpn.ovpn",
        )
        assertEquals(ProfileType.OPENVPN, profile.type)
        assertEquals("203.0.113.10", profile.server)
        assertEquals(1194, profile.port)
        val out = outbound(profile.outboundJson)
        assertEquals("udp", out["proto"]!!.jsonPrimitive.content)
        assertEquals("UDP", OvpnConfParser.protocolLabel(profile.outboundJson))
        assertEquals(1194, out["server_port"]!!.jsonPrimitive.int)
    }

    @Test
    fun `global proto applies to remotes without their own protocol`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            proto tcp
            remote vpn.example.com 1194
            """.trimIndent(),
            "vpn.ovpn",
        )
        val out = outbound(profile.outboundJson)
        assertEquals("tcp", out["proto"]!!.jsonPrimitive.content)
        assertEquals("tcp", out["remotes"]!!.jsonArray[0].jsonObject["proto"]!!.jsonPrimitive.content)
    }

    @Test
    fun `global proto applies even when written after the remote lines`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            remote vpn.example.com 1194
            proto tcp
            """.trimIndent(),
            "vpn.ovpn",
        )
        val out = outbound(profile.outboundJson)
        assertEquals("tcp", out["proto"]!!.jsonPrimitive.content)
        assertEquals("tcp", out["remotes"]!!.jsonArray[0].jsonObject["proto"]!!.jsonPrimitive.content)
    }

    @Test
    fun `connection block proto applies to its remote even when written after it`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            <connection>
            remote vpn.example.com 1194
            proto tcp
            </connection>
            """.trimIndent(),
            "vpn.ovpn",
        )
        val out = outbound(profile.outboundJson)
        assertEquals("tcp", out["proto"]!!.jsonPrimitive.content)
        assertEquals("tcp", out["remotes"]!!.jsonArray[0].jsonObject["proto"]!!.jsonPrimitive.content)
    }

    @Test
    fun `embedded ca cert key blocks are kept literally and flagged`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            proto udp
            remote vpn.example.com 1194 udp
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB
            -----END CERTIFICATE-----
            </ca>
            <cert>
            -----BEGIN CERTIFICATE-----
            MIIC
            -----END CERTIFICATE-----
            </cert>
            <key>
            -----BEGIN PRIVATE KEY-----
            MIIE
            -----END PRIVATE KEY-----
            </key>
            """.trimIndent(),
            "vpn.ovpn",
        )
        val out = outbound(profile.outboundJson)
        assertTrue(out["has_embedded_certs"]!!.jsonPrimitive.boolean)
        val config = OvpnConfParser.configTextOf(profile.outboundJson)
        assertTrue(config.contains("<ca>\n-----BEGIN CERTIFICATE-----\nMIIB\n-----END CERTIFICATE-----\n</ca>"))
        assertTrue(config.contains("<cert>"))
        assertTrue(config.contains("-----BEGIN PRIVATE KEY-----"))
    }

    @Test
    fun `multiple remotes first shown all kept in remotes`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            remote first.example.com 1194 udp
            remote second.example.com 443 tcp
            <connection>
            remote third.example.com 1194 udp
            </connection>
            """.trimIndent(),
            "vpn.ovpn",
        )
        assertEquals("first.example.com", profile.server)
        assertEquals(1194, profile.port)
        val remotes = outbound(profile.outboundJson)["remotes"]!!.jsonArray
        assertEquals(3, remotes.size)
        assertEquals("first.example.com", remotes[0].jsonObject["host"]!!.jsonPrimitive.content)
        assertEquals(1194, remotes[0].jsonObject["port"]!!.jsonPrimitive.int)
        assertEquals("udp", remotes[0].jsonObject["proto"]!!.jsonPrimitive.content)
        assertEquals("second.example.com", remotes[1].jsonObject["host"]!!.jsonPrimitive.content)
        assertEquals(443, remotes[1].jsonObject["port"]!!.jsonPrimitive.int)
        assertEquals("tcp", remotes[1].jsonObject["proto"]!!.jsonPrimitive.content)
        assertEquals("third.example.com", remotes[2].jsonObject["host"]!!.jsonPrimitive.content)
        // <connection> inner lines appear once, normalised, in config.
        val config = OvpnConfParser.configTextOf(profile.outboundJson)
        assertEquals(1, config.split("remote third.example.com 1194 udp").size - 1)
        assertTrue(config.contains("<connection>\nremote third.example.com 1194 udp\n</connection>"))
    }

    @Test
    fun `dev tap is rejected`() {
        assertRejected(
            """
            client
            dev tap
            remote vpn.example.com 1194
            """.trimIndent(),
        )
    }

    @Test
    fun `dev other than tun is rejected`() {
        assertRejected(
            """
            client
            dev null
            remote vpn.example.com 1194
            """.trimIndent(),
        )
    }

    @Test
    fun `missing remote is rejected`() {
        assertRejected(
            """
            client
            dev tun
            proto udp
            """.trimIndent(),
        )
    }

    @Test
    fun `empty file is rejected`() {
        assertRejected("   \n\n  ")
        assertRejected("")
    }

    @Test
    fun `external file reference is rejected`() {
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 1194
            ca ca.crt
            """.trimIndent(),
        )
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 1194
            cert client.pem
            """.trimIndent(),
        )
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 1194
            tls-auth ta.key 1
            """.trimIndent(),
        )
    }

    @Test
    fun `auth-user-pass pointing at a file is rejected`() {
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 1194
            auth-user-pass /etc/openvpn/login.conf
            """.trimIndent(),
        )
    }

    @Test
    fun `bare auth-user-pass is accepted and flagged without credentials`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            remote vpn.example.com 1194
            auth-user-pass
            remote-cert-tls server
            """.trimIndent(),
            "vpn.ovpn",
        )
        val out = outbound(profile.outboundJson)
        assertTrue(out["auth_user_pass"]!!.jsonPrimitive.boolean)
        assertFalse("username" in out)
        assertFalse("password" in out)
    }

    @Test
    fun `embedded auth-user-pass block yields username and password`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            remote vpn.example.com 1194
            auth-user-pass
            <auth-user-pass>
            alice
            s3cret
            </auth-user-pass>
            """.trimIndent(),
            "vpn.ovpn",
        )
        val out = outbound(profile.outboundJson)
        assertTrue(out["auth_user_pass"]!!.jsonPrimitive.boolean)
        assertEquals("alice", out["username"]!!.jsonPrimitive.content)
        assertEquals("s3cret", out["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun `comments and blank lines are removed from config`() {
        val profile = OvpnConfParser.parseConf(
            """
            # a full-line comment
            ; another comment

            client   # trailing comment
            dev tun ; trailing semicolon comment
            remote vpn.example.com 1194 udp

            # tail comment
            """.trimIndent(),
            "vpn.ovpn",
        )
        val config = OvpnConfParser.configTextOf(profile.outboundJson)
        assertEquals(
            "client\ndev tun\nremote vpn.example.com 1194 udp",
            config,
        )
    }

    @Test
    fun `protocolLabel defaults to UDP when proto is missing or unreadable`() {
        assertEquals("UDP", OvpnConfParser.protocolLabel("{}"))
        assertEquals("UDP", OvpnConfParser.protocolLabel("not json"))
        assertEquals("UDP", OvpnConfParser.protocolLabel(""))
        val profile = OvpnConfParser.parseConf(
            "client\ndev tun\nremote vpn.example.com 1194\n",
            "vpn.ovpn",
        )
        assertEquals("UDP", OvpnConfParser.protocolLabel(profile.outboundJson))
    }

    @Test
    fun `configTextOf round-trips the normalised config`() {
        val profile = OvpnConfParser.parseConf(
            """
            client
            dev tun
            remote vpn.example.com 1194 udp
            """.trimIndent(),
            "vpn.ovpn",
        )
        assertEquals(
            "client\ndev tun\nremote vpn.example.com 1194 udp",
            OvpnConfParser.configTextOf(profile.outboundJson),
        )
        assertEquals("", OvpnConfParser.configTextOf("{}"))
        assertEquals("", OvpnConfParser.configTextOf("garbage"))
    }

    @Test
    fun `name comes from file name without ovpn extension`() {
        val profile = OvpnConfParser.parseConf(
            "client\ndev tun\nremote vpn.example.com 1194\n",
            "My Server.ovpn",
        )
        assertEquals("My Server", profile.name)
    }

    @Test
    fun `blank file name falls back to OpenVPN host`() {
        val profile = OvpnConfParser.parseConf(
            "client\ndev tun\nremote vpn.example.com 1194\n",
            "",
        )
        assertEquals("OpenVPN vpn.example.com", profile.name)
        val noExt = OvpnConfParser.parseConf(
            "client\ndev tun\nremote vpn.example.com 1194\n",
            "myserver",
        )
        assertEquals("myserver", noExt.name)
    }

    @Test
    fun `unknown protocol is rejected`() {
        assertRejected(
            """
            client
            dev tun
            proto sctp
            remote vpn.example.com 1194
            """.trimIndent(),
        )
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 1194 gre
            """.trimIndent(),
        )
    }

    @Test
    fun `invalid port in remote is rejected`() {
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com notaport
            """.trimIndent(),
        )
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 70000
            """.trimIndent(),
        )
    }

    @Test
    fun `unclosed inline block is rejected`() {
        assertRejected(
            """
            client
            dev tun
            remote vpn.example.com 1194
            <ca>
            -----BEGIN CERTIFICATE-----
            MIIB
            """.trimIndent(),
        )
    }
}
