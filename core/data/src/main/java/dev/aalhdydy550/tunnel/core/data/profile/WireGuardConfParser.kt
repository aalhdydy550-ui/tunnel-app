package dev.aalhdydy550.tunnel.core.data.profile

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses a WireGuard `.conf` file (the format `wg-quick` and the official
 * WireGuard apps use) into a [Profile].
 *
 * WireGuard has no stable link scheme, so file import is the v0.1 path.
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 */
object WireGuardConfParser {

    fun parseConf(text: String, fallbackName: String = ""): Profile {
        val sections = parseIni(text)
        val iface = sections["Interface"] ?: throw IllegalArgumentException("missing [Interface] section")
        val peer = sections["Peer"] ?: throw IllegalArgumentException("missing [Peer] section")

        val privateKey = iface["PrivateKey"]?.trim().orEmpty()
        require(privateKey.isNotBlank()) { "missing PrivateKey" }
        val publicKey = peer["PublicKey"]?.trim().orEmpty()
        require(publicKey.isNotBlank()) { "missing PublicKey" }
        val endpoint = peer["Endpoint"]?.trim().orEmpty()
        require(endpoint.isNotBlank()) { "missing Endpoint" }
        val (host, port) = splitHostPort(endpoint)

        val addresses = iface["Address"]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        require(addresses.isNotEmpty()) { "missing Address" }

        val outbound = buildJsonObject {
            put("type", "wireguard")
            put("server", host)
            put("server_port", port)
            putJsonArray("local_address") { addresses.forEach { add(JsonPrimitive(it)) } }
            put("private_key", privateKey)
            put("peer_public_key", publicKey)
            peer["PresharedKey"]?.trim()?.takeIf { it.isNotBlank() }?.let { put("pre_shared_key", it) }
            iface["MTU"]?.trim()?.toIntOrNull()?.let { put("mtu", it) }
        }
        return Profile(
            name = fallbackName.ifBlank { "WireGuard $host" },
            type = ProfileType.WIREGUARD,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    private fun parseIni(text: String): Map<String, Map<String, String>> {
        val sections = linkedMapOf<String, MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[") && line.endsWith("]")) {
                val name = line.substring(1, line.length - 1).trim()
                current = sections.getOrPut(name) { linkedMapOf() }
                continue
            }
            val eq = line.indexOf('=')
            if (eq > 0 && current != null) {
                current[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
            }
        }
        return sections
    }

    private fun splitHostPort(endpoint: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (endpoint.startsWith("[")) {
            val close = endpoint.indexOf(']')
            require(close > 0) { "malformed IPv6 endpoint" }
            host = endpoint.substring(1, close)
            require(close + 1 < endpoint.length && endpoint[close + 1] == ':') {
                "missing port after IPv6 endpoint"
            }
            portText = endpoint.substring(close + 2)
        } else {
            val colon = endpoint.lastIndexOf(':')
            require(colon > 0) { "missing port in Endpoint" }
            host = endpoint.substring(0, colon)
            portText = endpoint.substring(colon + 1)
        }
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65535) { "invalid port in Endpoint: $portText" }
        require(host.isNotBlank()) { "missing host in Endpoint" }
        return host to port
    }
}
