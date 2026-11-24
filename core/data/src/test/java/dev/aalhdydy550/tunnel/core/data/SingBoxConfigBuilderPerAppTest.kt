package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the per-app proxy fields of [SingBoxConfigBuilder]. */
class SingBoxConfigBuilderPerAppTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun vlessProfile(): Profile = ProfileLinkParser.parseLink(
        "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
            "?encryption=none&security=tls&sni=example.com&type=ws&path=%2Fws#Test",
    )

    private fun tunInbound(config: String) =
        json.parseToJsonElement(config).jsonObject["inbounds"]!!
            .jsonArray[0].jsonObject

    @Test
    fun `default config carries neither package list`() {
        val tun = tunInbound(SingBoxConfigBuilder.buildConfig(vlessProfile()))
        assertNull(tun["include_package"])
        assertNull(tun["exclude_package"])
    }

    @Test
    fun `includePackages emits include_package only`() {
        val tun = tunInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                includePackages = listOf("com.a", "com.b"),
            ),
        )
        assertEquals(
            listOf("com.a", "com.b"),
            tun["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(tun["exclude_package"])
    }

    @Test
    fun `excludePackages emits exclude_package only`() {
        val tun = tunInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                excludePackages = listOf("com.bank"),
            ),
        )
        assertEquals(
            listOf("com.bank"),
            tun["exclude_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(tun["include_package"])
    }

    @Test
    fun `when both lists are passed only include_package is emitted`() {
        // sing-box rejects a config carrying both fields.
        val tun = tunInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                includePackages = listOf("com.a"),
                excludePackages = listOf("com.b"),
            ),
        )
        assertEquals(
            listOf("com.a"),
            tun["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertNull(tun["exclude_package"])
    }

    @Test
    fun `blank package entries are dropped`() {
        val tun = tunInbound(
            SingBoxConfigBuilder.buildConfig(
                vlessProfile(),
                includePackages = listOf("com.a", "", "   "),
            ),
        )
        assertEquals(
            listOf("com.a"),
            tun["include_package"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }
}
