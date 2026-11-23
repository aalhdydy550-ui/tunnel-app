package dev.aalhdydy550.tunnel.core.data.profile

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Parses subscription-style proxy links into [Profile]s.
 *
 * Supported schemes (standard link formats used by open-source
 * sing-box / V2Ray clients):
 * `vless://`, `vmess://` (base64 JSON), `trojan://`, `ss://` (three forms),
 * `hysteria2://` / `hy2://`, `tuic://`, `ssh://` (user[:password]@host:port).
 *
 * Optional speed/fragment parameters on URI-form links (our own
 * extension, documented in `docs/CONNECTIONS.md`):
 * `?mux=1` enables sing-box multiplex (h2mux) on VLESS/Trojan;
 * `?fragment=1` enables TLS fragmenting in the TLS object;
 * `?ports=20000-30000,40000` on Hysteria2 enables client port hopping
 * (`server_ports`, sing-box 1.11+); `?hop-interval=30s` sets the hop
 * interval.
 *
 * WireGuard has no stable link scheme, so it is imported from a `.conf` file
 * instead — see [WireGuardConfParser].
 *
 * `zivpn://` links are recognized and rejected with a clear message:
 * ZIVPN is a Phase 2 protocol (external process + local SOCKS5), not
 * connectable in v0.1 — see `docs/PHASE2.md`.
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 */
object ProfileLinkParser {

    data class ImportResult(val profiles: List<Profile>, val errors: List<String>)

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses every non-empty, non-comment line of [text] as a link. */
    fun parseText(text: String): ImportResult {
        val profiles = mutableListOf<Profile>()
        val errors = mutableListOf<String>()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            try {
                profiles += parseLink(line)
            } catch (e: IllegalArgumentException) {
                errors += "«${line.take(80)}»: ${e.message}"
            }
        }
        return ImportResult(profiles, errors)
    }

    fun parseLink(link: String): Profile {
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("vless://") -> parseVless(trimmed)
            trimmed.startsWith("vmess://") -> parseVmess(trimmed)
            trimmed.startsWith("trojan://") -> parseTrojan(trimmed)
            trimmed.startsWith("ss://") -> parseShadowsocks(trimmed)
            trimmed.startsWith("hysteria2://") || trimmed.startsWith("hy2://") -> parseHysteria2(trimmed)
            trimmed.startsWith("tuic://") -> parseTuic(trimmed)
            trimmed.startsWith("ssh://") -> parseSsh(trimmed)
            trimmed.startsWith("zivpn://") -> throw IllegalArgumentException(
                "ZIVPN is planned for Phase 2 (external process + local SOCKS5) " +
                    "and cannot connect in v0.1 — see docs/PHASE2.md",
            )
            else -> throw IllegalArgumentException("unsupported link scheme")
        }
    }

    // ------------------------------------------------------------------ shared

    private data class LinkParts(
        val main: String,
        val name: String,
        val query: Map<String, String>,
    )

    private fun splitLink(link: String, scheme: String): LinkParts {
        val withoutScheme = link.substring(scheme.length)
        val hashIndex = withoutScheme.indexOf('#')
        val mainWithQuery = if (hashIndex >= 0) withoutScheme.substring(0, hashIndex) else withoutScheme
        val name = if (hashIndex >= 0) {
            urlDecode(withoutScheme.substring(hashIndex + 1)).ifBlank { "" }
        } else {
            ""
        }
        val queryIndex = mainWithQuery.indexOf('?')
        val main = if (queryIndex >= 0) mainWithQuery.substring(0, queryIndex) else mainWithQuery
        val query = if (queryIndex >= 0) {
            parseQuery(mainWithQuery.substring(queryIndex + 1))
        } else {
            emptyMap()
        }
        return LinkParts(main, name, query)
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            if (eq < 0) {
                result[urlDecode(pair)] = ""
            } else {
                result[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return result
    }

    /** Splits `userinfo@host:port` (host may be `[ipv6]:port`). */
    private fun splitUserInfoHostPort(main: String): Triple<String, String, Int> {
        val at = main.lastIndexOf('@')
        require(at > 0) { "missing '@' in link" }
        val userInfo = main.substring(0, at)
        val hostPort = main.substring(at + 1)
        val (host, port) = splitHostPort(hostPort)
        return Triple(userInfo, host, port)
    }

    private fun splitHostPort(hostPort: String): Pair<String, Int> {
        val host: String
        val portText: String
        if (hostPort.startsWith("[")) {
            val close = hostPort.indexOf(']')
            require(close > 0) { "malformed IPv6 host" }
            host = hostPort.substring(1, close)
            require(close + 1 < hostPort.length && hostPort[close + 1] == ':') {
                "missing port after IPv6 host"
            }
            portText = hostPort.substring(close + 2)
        } else {
            val colon = hostPort.lastIndexOf(':')
            require(colon > 0) { "missing port" }
            host = hostPort.substring(0, colon)
            portText = hostPort.substring(colon + 1)
        }
        val port = portText.toIntOrNull()
        require(port != null && port in 1..65535) { "invalid port: $portText" }
        require(host.isNotBlank()) { "missing host" }
        return host to port
    }

    private fun decodeBase64(text: String): ByteArray {
        val normalized = text.trim().replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return try {
            Base64.getDecoder().decode(padded)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("invalid base64 payload")
        }
    }

    private fun decodeBase64ToString(text: String): String =
        String(decodeBase64(text), StandardCharsets.UTF_8)

    private fun urlDecode(text: String): String = try {
        URLDecoder.decode(text, StandardCharsets.UTF_8.name())
    } catch (e: IllegalArgumentException) {
        text
    }

    private fun displayName(name: String, host: String, port: Int): String =
        name.ifBlank { "$host:$port" }

    // ------------------------------------------------------------------ TLS

    private fun tlsObject(
        query: Map<String, String>,
        host: String,
        security: String,
    ): JsonObject? {
        if (security == "none" || security.isEmpty()) return null
        return buildJsonObject {
            put("enabled", true)
            val sni = query["sni"]?.ifBlank { null } ?: host
            put("server_name", sni)
            val insecure = query["allowInsecure"] == "1" || query["allowInsecure"] == "true"
            if (insecure) put("insecure", true)
            query["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                putJsonArray("alpn") { alpn.split(',').forEach { add(JsonPrimitive(it.trim())) } }
            }
            val fingerprint = query["fp"]?.takeIf { it.isNotBlank() }
            if (fingerprint != null) {
                putJsonObject("utls") {
                    put("enabled", true)
                    put("fingerprint", fingerprint)
                }
            }
            // TLS fragmenting (sing-box 1.12+): splits the ClientHello so
            // middleboxes see smaller fragments. Opt-in via ?fragment=1.
            if (query["fragment"] == "1" || query["fragment"] == "true") {
                put("fragment", true)
            }
            if (security == "reality") {
                val publicKey = query["pbk"] ?: ""
                val shortId = query["sid"] ?: ""
                putJsonObject("reality") {
                    put("enabled", true)
                    put("public_key", publicKey)
                    put("short_id", shortId)
                }
            }
        }
    }

    private fun transportObject(query: Map<String, String>): JsonObject? {
        return when (val type = (query["type"] ?: "tcp").lowercase()) {
            "tcp" -> null
            "ws" -> buildJsonObject {
                put("type", "ws")
                query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                val wsHost = query["host"]?.takeIf { it.isNotBlank() }
                if (wsHost != null) {
                    putJsonObject("headers") { put("Host", wsHost) }
                }
            }
            "grpc" -> buildJsonObject {
                put("type", "grpc")
                query["serviceName"]?.takeIf { it.isNotBlank() }?.let { put("service_name", it) }
            }
            "httpupgrade" -> buildJsonObject {
                put("type", "httpupgrade")
                query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let { put("host", it) }
            }
            "h2" -> buildJsonObject {
                put("type", "http")
                query["path"]?.takeIf { it.isNotBlank() }?.let { put("path", it) }
                query["host"]?.takeIf { it.isNotBlank() }?.let {
                    putJsonArray("host") { it.split(',').forEach { h -> add(JsonPrimitive(h.trim())) } }
                }
            }
            else -> throw IllegalArgumentException("unsupported transport: $type")
        }
    }

    // ------------------------------------------------------------------ VLESS

    /**
     * Opt-in multiplex (`?mux=1` on the link): sing-box `multiplex`
     * (h2mux, 4 connections / 4 streams). Off by default — some servers
     * do not support mux, and a forced mux would break them. The user
     * enables it per link when the server supports it.
     */
    private fun kotlinx.serialization.json.JsonObjectBuilder.addMultiplexIfRequested(
        query: Map<String, String>,
    ) {
        if (query["mux"] == "1" || query["mux"] == "true") {
            putJsonObject("multiplex") {
                put("enabled", true)
                put("protocol", "h2mux")
                put("max_connections", 4)
                put("min_streams", 4)
            }
        }
    }

    private fun parseVless(link: String): Profile {
        val parts = splitLink(link, "vless://")
        val (uuid, host, port) = splitUserInfoHostPort(parts.main)
        require(uuid.isNotBlank()) { "missing UUID" }
        val query = parts.query
        val security = (query["security"] ?: "none").lowercase()
        val outbound = buildJsonObject {
            put("type", "vless")
            put("server", host)
            put("server_port", port)
            put("uuid", urlDecode(uuid))
            query["flow"]?.takeIf { it.isNotBlank() }?.let { put("flow", it) }
            tlsObject(query, host, security)?.let { put("tls", it) }
            transportObject(query)?.let { put("transport", it) }
            addMultiplexIfRequested(query)
        }
        return Profile(
            name = displayName(parts.name, host, port),
            type = ProfileType.VLESS,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ VMess

    private fun parseVmess(link: String): Profile {
        val parts = splitLink(link, "vmess://")
        val payload = try {
            json.parseToJsonElement(decodeBase64ToString(parts.main)).jsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("invalid vmess JSON payload")
        }
        val host = payload["add"]?.jsonPrimitive?.content?.trim().orEmpty()
        val port = payload["port"]?.jsonPrimitive?.content?.trim()?.toIntOrNull()
        val uuid = payload["id"]?.jsonPrimitive?.content?.trim().orEmpty()
        require(host.isNotBlank()) { "vmess: missing address" }
        require(port != null && port in 1..65535) { "vmess: invalid port" }
        require(uuid.isNotBlank()) { "vmess: missing id" }
        val net = (payload["net"]?.jsonPrimitive?.content ?: "tcp").lowercase()
        val tls = payload["tls"]?.jsonPrimitive?.content ?: ""
        val query = mapOf(
            "type" to net,
            "path" to (payload["path"]?.jsonPrimitive?.content ?: ""),
            "host" to (payload["host"]?.jsonPrimitive?.content ?: ""),
            "serviceName" to (payload["path"]?.jsonPrimitive?.content ?: ""),
            "sni" to (payload["sni"]?.jsonPrimitive?.content ?: ""),
            "alpn" to (payload["alpn"]?.jsonPrimitive?.content ?: ""),
            "fp" to (payload["fp"]?.jsonPrimitive?.content ?: ""),
        )
        val outbound = buildJsonObject {
            put("type", "vmess")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("security", payload["scy"]?.jsonPrimitive?.content?.ifBlank { "auto" } ?: "auto")
            put("alter_id", payload["aid"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            if (tls == "tls") {
                tlsObject(query, host, "tls")?.let { put("tls", it) }
            }
            transportObject(query)?.let { put("transport", it) }
        }
        val name = payload["ps"]?.jsonPrimitive?.content?.trim().orEmpty()
        return Profile(
            name = displayName(name.ifBlank { parts.name }, host, port),
            type = ProfileType.VMESS,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ Trojan

    private fun parseTrojan(link: String): Profile {
        val parts = splitLink(link, "trojan://")
        val (password, host, port) = splitUserInfoHostPort(parts.main)
        require(password.isNotBlank()) { "missing password" }
        val query = parts.query
        val outbound = buildJsonObject {
            put("type", "trojan")
            put("server", host)
            put("server_port", port)
            put("password", urlDecode(password))
            tlsObject(query, host, "tls")?.let { put("tls", it) }
            transportObject(query)?.let { put("transport", it) }
            addMultiplexIfRequested(query)
        }
        return Profile(
            name = displayName(parts.name, host, port),
            type = ProfileType.TROJAN,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ Shadowsocks

    private fun parseShadowsocks(link: String): Profile {
        val parts = splitLink(link, "ss://")
        val main = parts.main
        val method: String
        val password: String
        val host: String
        val port: Int
        if ('@' in main) {
            // ss://base64(method:password)@host:port  OR  ss://method:password@host:port
            val at = main.lastIndexOf('@')
            val userInfo = main.substring(0, at)
            val hostPort = main.substring(at + 1)
            val decoded = if (userInfo.contains(':')) userInfo else decodeBase64ToString(userInfo)
            val colon = decoded.indexOf(':')
            require(colon > 0) { "ss: missing method separator" }
            method = decoded.substring(0, colon)
            password = decoded.substring(colon + 1)
            val hp = splitHostPort(hostPort)
            host = hp.first
            port = hp.second
        } else {
            // ss://base64(method:password@host:port)
            val decoded = decodeBase64ToString(main)
            val at = decoded.lastIndexOf('@')
            require(at > 0) { "ss: missing '@' in decoded payload" }
            val userInfo = decoded.substring(0, at)
            val colon = userInfo.indexOf(':')
            require(colon > 0) { "ss: missing method separator" }
            method = userInfo.substring(0, colon)
            password = userInfo.substring(colon + 1)
            val hp = splitHostPort(decoded.substring(at + 1))
            host = hp.first
            port = hp.second
        }
        require(method.isNotBlank()) { "ss: missing method" }
        val outbound = buildJsonObject {
            put("type", "shadowsocks")
            put("server", host)
            put("server_port", port)
            put("method", method)
            put("password", password)
        }
        return Profile(
            name = displayName(parts.name, host, port),
            type = ProfileType.SHADOWSOCKS,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ Hysteria2

    private fun parseHysteria2(link: String): Profile {
        val scheme = if (link.startsWith("hysteria2://")) "hysteria2://" else "hy2://"
        val parts = splitLink(link, scheme)
        val (password, host, port) = splitUserInfoHostPort(parts.main)
        require(password.isNotBlank()) { "hysteria2: missing password" }
        val query = parts.query
        val outbound = buildJsonObject {
            put("type", "hysteria2")
            put("server", host)
            // Port hopping (sing-box 1.11+): ?ports=20000-30000,40000 sets
            // server_ports and replaces server_port (they conflict).
            val portRanges = query["ports"]?.split(',')
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            if (portRanges.isEmpty()) {
                put("server_port", port)
            } else {
                putJsonArray("server_ports") {
                    portRanges.forEach { add(JsonPrimitive(it)) }
                }
            }
            query["hop-interval"]?.takeIf { it.isNotBlank() }?.let { put("hop_interval", it) }
            put("password", urlDecode(password))
            val tls = buildJsonObject {
                put("enabled", true)
                put("server_name", query["sni"]?.ifBlank { null } ?: host)
                if (query["insecure"] == "1" || query["insecure"] == "true") put("insecure", true)
                putJsonArray("alpn") { add(JsonPrimitive("h3")) }
            }
            put("tls", tls)
            val obfs = query["obfs"]?.takeIf { it.isNotBlank() }
            if (obfs != null) {
                putJsonObject("obfs") {
                    put("type", obfs)
                    query["obfs-password"]?.takeIf { it.isNotBlank() }?.let { put("password", it) }
                }
            }
            query["upmbps"]?.toIntOrNull()?.let { put("up_mbps", it) }
            query["downmbps"]?.toIntOrNull()?.let { put("down_mbps", it) }
        }
        return Profile(
            name = displayName(parts.name, host, port),
            type = ProfileType.HYSTERIA2,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ TUIC

    private fun parseTuic(link: String): Profile {
        val parts = splitLink(link, "tuic://")
        val (userInfo, host, port) = splitUserInfoHostPort(parts.main)
        val colon = userInfo.indexOf(':')
        require(colon > 0) { "tuic: expected uuid:password" }
        val uuid = userInfo.substring(0, colon)
        val password = userInfo.substring(colon + 1)
        require(uuid.isNotBlank() && password.isNotBlank()) { "tuic: missing uuid or password" }
        val query = parts.query
        val outbound = buildJsonObject {
            put("type", "tuic")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("password", password)
            query["congestion_control"]?.takeIf { it.isNotBlank() }?.let { put("congestion_control", it) }
            val tls = buildJsonObject {
                put("enabled", true)
                put("server_name", query["sni"]?.ifBlank { null } ?: host)
                query["alpn"]?.takeIf { it.isNotBlank() }?.let { alpn ->
                    putJsonArray("alpn") { alpn.split(',').forEach { add(JsonPrimitive(it.trim())) } }
                }
            }
            put("tls", tls)
            query["udp_relay_mode"]?.takeIf { it.isNotBlank() }?.let { put("udp_relay_mode", it) }
        }
        return Profile(
            name = displayName(parts.name, host, port),
            type = ProfileType.TUIC,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }

    // ------------------------------------------------------------------ SSH

    /**
     * `ssh://user[:password]@host:port[?host-key=…&client-version=…]`
     * → sing-box `ssh` outbound (native in sing-box 1.14).
     *
     * Password auth is the link form. Key auth is NOT expressible in a
     * link — use a raw sing-box JSON profile with `private_key` instead.
     * The outbound forwards traffic like an SSH dynamic forward, the
     * same job the original's Trilead SSH service did, but inside the
     * single sing-box core instead of a separate service.
     */
    private fun parseSsh(link: String): Profile {
        val parts = splitLink(link, "ssh://")
        val (userInfo, host, port) = splitUserInfoHostPort(parts.main)
        val colon = userInfo.indexOf(':')
        val user = if (colon >= 0) userInfo.substring(0, colon) else userInfo
        val password = if (colon >= 0) userInfo.substring(colon + 1) else ""
        require(user.isNotBlank()) { "ssh: missing user" }
        val query = parts.query
        val outbound = buildJsonObject {
            put("type", "ssh")
            put("server", host)
            put("server_port", port)
            put("user", urlDecode(user))
            if (password.isNotBlank()) put("password", urlDecode(password))
            query["host-key"]?.takeIf { it.isNotBlank() }?.let { key ->
                putJsonArray("host_key") { add(JsonPrimitive(key)) }
            }
            query["client-version"]?.takeIf { it.isNotBlank() }?.let {
                put("client_version", it)
            }
        }
        return Profile(
            name = displayName(parts.name, host, port),
            type = ProfileType.SSH,
            server = host,
            port = port,
            outboundJson = outbound.toString(),
        )
    }
}
