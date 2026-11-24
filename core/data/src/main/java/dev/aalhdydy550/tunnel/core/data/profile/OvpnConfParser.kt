package dev.aalhdydy550.tunnel.core.data.profile

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Parses an OpenVPN `.ovpn` file into a [Profile].
 *
 * The stored [Profile.outboundJson] keeps the **normalised `.ovpn` text**
 * itself (comments stripped, blank lines dropped) because the external
 * OpenVPN engine parses that text with its own `ConfigParser` — this
 * parser only validates the file enough to display it (first `remote`,
 * protocol) and to reject files the app cannot use.
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 */
object OvpnConfParser {

    /**
     * Stored `outboundJson` schema:
     * ```
     * {"type":"openvpn","server":..,"server_port":..,"proto":"tcp"|"udp",
     *  "config":"<normalised .ovpn text>",
     *  "remotes":[{"host":..,"port":..,"proto":..}, ...],
     *  "auth_user_pass":true|false,"has_embedded_certs":true|false}
     * ```
     * `"username"` / `"password"` are present only when credentials are
     * actually embedded in the file itself (an `<auth-user-pass>` block,
     * or two plain username/password lines after a bare `auth-user-pass`
     * directive). A bare `auth-user-pass` sets `auth_user_pass=true`
     * with no credentials — the external app asks the user at connect
     * time. `server` / `server_port` / `proto` describe the first
     * `remote`; every `remote` (including ones inside `<connection>`
     * blocks) is kept in `remotes`.
     *
     * @throws IllegalArgumentException with an Arabic message when the
     * file is rejected (empty, `dev tap`, no `remote`, unknown
     * protocol, or a reference to an external file by path).
     */
    fun parseConf(text: String, fallbackName: String = ""): Profile {
        val entries = normalise(text)
        if (entries.isEmpty()) {
            throw IllegalArgumentException("ملف .ovpn فارغ — لا توجد أي توجيهات")
        }

        var globalProto: String? = null
        var authUserPass = false
        var username: String? = null
        var password: String? = null
        var hasEmbeddedCerts = false
        val remotes = mutableListOf<Remote>()
        val configLines = mutableListOf<String>()

        fun checkDev(value: String) {
            if (value != "tun" && !TUN_DEVICE_REGEX.matches(value)) {
                throw IllegalArgumentException(
                    "التوجيه dev يستخدم «$value» وهو غير مدعوم — التطبيق يدعم dev tun فقط",
                )
            }
        }

        fun checkFileDirective(keyword: String, args: List<String>) {
            if (args.isNotEmpty()) {
                throw IllegalArgumentException(
                    "التوجيه $keyword يشير إلى ملف خارجي (${args.first()}) — " +
                        "ضمّن المحتوى داخل الملف بين <$keyword> و</$keyword> أو أزل التوجيه",
                )
            }
        }

        fun parseRemote(args: List<String>, protoContext: String?): Remote {
            if (args.isEmpty() || args.size > 3) {
                throw IllegalArgumentException("سطر remote غير صالح — الصيغة: remote <الخادم> [المنفذ] [tcp|udp]")
            }
            val host = args[0].removeSurrounding("[", "]")
            if (host.isBlank()) {
                throw IllegalArgumentException("سطر remote بلا خادم")
            }
            val port = if (args.size >= 2) {
                args[1].toIntOrNull()?.takeIf { it in 1..65535 }
                    ?: throw IllegalArgumentException("منفذ غير صالح في سطر remote: ${args[1]}")
            } else {
                DEFAULT_PORT
            }
            val proto = if (args.size >= 3) normaliseProto(args[2]) else protoContext
            return Remote(host, port, proto)
        }

        var i = 0
        while (i < entries.size) {
            when (val entry = entries[i]) {
                is Entry.Directive -> {
                    configLines += entry.line
                    val keyword = entry.keyword
                    val args = entry.args
                    when (keyword) {
                        "client", "remote-cert-tls", "compress", "resolv-retry",
                        "nobind", "redirect-gateway", "remote-random",
                        -> Unit // accepted, passed through as-is
                        "remote" -> remotes += parseRemote(args, null)
                        "proto" -> {
                            if (args.size != 1) {
                                throw IllegalArgumentException("التوجيه proto يحتاج قيمة واحدة: tcp أو udp")
                            }
                            globalProto = normaliseProto(args[0])
                        }
                        "dev" -> {
                            if (args.size != 1) {
                                throw IllegalArgumentException("التوجيه dev يحتاج قيمة واحدة: tun")
                            }
                            checkDev(args[0])
                        }
                        "dev-type" -> {
                            if (args.size != 1) {
                                throw IllegalArgumentException("التوجيه dev-type يحتاج قيمة واحدة: tun")
                            }
                            checkDev(args[0])
                        }
                        "cipher", "auth" -> Unit // accepted, passed through as-is
                        "auth-user-pass" -> {
                            checkFileDirective(keyword, args)
                            authUserPass = true
                            // Credentials embedded as two plain lines after the
                            // directive (a form some exporters use): the first
                            // line must be a single token that is not a known
                            // directive, the second line is the password.
                            // Anything else stays a bare flag and the
                            // external app asks the user at connect time.
                            val next1 = entries.getOrNull(i + 1) as? Entry.Directive
                            val next2 = entries.getOrNull(i + 2) as? Entry.Directive
                            if (username == null && next1 != null && next2 != null &&
                                next1.isSingleNonDirectiveToken() && next2.line.isNotBlank()
                            ) {
                                username = next1.line
                                password = next2.line
                            }
                        }
                        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2",
                        "crl-verify", "pkcs12", "dh",
                        -> {
                            checkFileDirective(keyword, args)
                            // A bare directive must be followed immediately by
                            // its inline block; otherwise it points at nothing.
                            val next = entries.getOrNull(i + 1)
                            if (next !is Entry.Block || next.name != keyword) {
                                throw IllegalArgumentException(
                                    "التوجيه $keyword بلا قيمة — أضف الكتلة المضمّنة <$keyword>...</$keyword> أو أزل التوجيه",
                                )
                            }
                        }
                        "up", "down" -> {
                            if (args.isNotEmpty()) {
                                throw IllegalArgumentException(
                                    "التوجيه $keyword يشير إلى ملف خارجي (${args.first()}) — أزل التوجيه",
                                )
                            }
                        }
                        else -> Unit // unknown directive: passed through untouched
                    }
                }
                is Entry.Block -> {
                    when (entry.name) {
                        "ca", "cert", "key", "tls-auth", "tls-crypt", "tls-crypt-v2" -> {
                            configLines += entry.lines
                            hasEmbeddedCerts = true
                        }
                        "auth-user-pass" -> {
                            configLines += entry.lines
                            authUserPass = true
                            val creds = entry.lines
                                .drop(1).dropLast(1)
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            if (creds.size == 2 && username == null) {
                                username = creds[0]
                                password = creds[1]
                            }
                        }
                        "connection" -> {
                            // Kept normalised like top-level directives
                            // (not literally): its lines are parsed.
                            configLines += entry.lines.first()
                            // The block's proto is the last `proto` line
                            // inside it, wherever it appears relative to
                            // its `remote` lines — resolve it first.
                            var connectionProto: String? = null
                            for (inner in entry.innerDirectives) {
                                if (inner.keyword == "proto") {
                                    if (inner.args.size != 1) {
                                        throw IllegalArgumentException("التوجيه proto يحتاج قيمة واحدة: tcp أو udp")
                                    }
                                    connectionProto = normaliseProto(inner.args[0])
                                }
                            }
                            for (inner in entry.innerDirectives) {
                                configLines += inner.line
                                when (inner.keyword) {
                                    "remote" -> remotes += parseRemote(inner.args, connectionProto)
                                    "proto" -> Unit // already resolved above
                                }
                            }
                            configLines += entry.lines.last()
                        }
                        else -> configLines += entry.lines // unknown block: kept literally
                    }
                }
            }
            i++
        }

        if (remotes.isEmpty()) {
            throw IllegalArgumentException("لا يوجد أي remote في الملف — أضف سطر remote بالخادم والمنفذ")
        }
        // A missing `dev` is fine (OpenVPN defaults to tun); only an
        // explicit non-tun device is rejected, in checkDev above.

        // Resolve each remote's protocol now that the file-wide `proto`
        // is known even when it appears after the `remote` lines.
        val resolved = remotes.map { remote ->
            remote.copy(proto = remote.proto ?: globalProto ?: DEFAULT_PROTO)
        }
        val first = resolved.first()
        val config = configLines.joinToString("\n")

        val outbound = buildJsonObject {
            put("type", "openvpn")
            put("server", first.host)
            put("server_port", first.port)
            put("proto", first.proto)
            put("config", config)
            putJsonArray("remotes") {
                resolved.forEach { remote ->
                    add(
                        buildJsonObject {
                            put("host", remote.host)
                            put("port", remote.port)
                            put("proto", remote.proto)
                        },
                    )
                }
            }
            put("auth_user_pass", authUserPass)
            put("has_embedded_certs", hasEmbeddedCerts)
            username?.let { put("username", it) }
            password?.let { put("password", it) }
        }

        val name = deriveName(fallbackName, first.host)
        return Profile(
            name = name,
            type = ProfileType.OPENVPN,
            server = first.host,
            port = first.port,
            outboundJson = outbound.toString(),
        )
    }

    /** "TCP" or "UDP" from the stored `proto` field; "UDP" when absent or unreadable. */
    fun protocolLabel(outboundJson: String): String {
        val proto = readField(outboundJson, "proto")?.lowercase()
        return when (proto) {
            "tcp" -> "TCP"
            "udp" -> "UDP"
            else -> "UDP"
        }
    }

    /** The stored normalised `.ovpn` text, or "" when absent. */
    fun configTextOf(outboundJson: String): String =
        readField(outboundJson, "config") ?: ""

    private fun readField(outboundJson: String, field: String): String? = try {
        Json.parseToJsonElement(outboundJson).jsonObject[field]?.jsonPrimitive?.content
    } catch (e: Exception) {
        null
    }

    private fun deriveName(fallbackName: String, host: String): String {
        var name = fallbackName.trim()
        name = name.substringAfterLast('/').substringAfterLast('\\')
        if (name.lowercase().endsWith(".ovpn")) {
            name = name.dropLast(".ovpn".length)
        }
        name = name.trim()
        return name.ifBlank { "OpenVPN $host" }
    }

    private fun normaliseProto(value: String): String = when (value.lowercase()) {
        "tcp", "tcp-client", "tcp-server", "tcp4", "tcp6" -> "tcp"
        "udp", "udp4", "udp6" -> "udp"
        else -> throw IllegalArgumentException(
            "البروتوكول «$value» غير مدعوم — استخدم tcp أو udp",
        )
    }

    /**
     * Normalises the file into entries: blank lines and full-line comments
     * are dropped, trailing comments (after a blank) are cut, and inline
     * blocks `<xxx>...</xxx>` are kept literally, line by line.
     */
    private fun normalise(text: String): List<Entry> {
        val entries = mutableListOf<Entry>()
        val lines = text.lines()
        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) {
                i++
                continue
            }
            val open = BLOCK_OPEN_REGEX.matchEntire(trimmed)
            if (open != null) {
                val name = open.groupValues[1].lowercase()
                val blockLines = mutableListOf(raw)
                var j = i + 1
                var closed = false
                while (j < lines.size) {
                    blockLines += lines[j]
                    if (lines[j].trim().equals("</$name>", ignoreCase = true)) {
                        closed = true
                        break
                    }
                    j++
                }
                if (!closed) {
                    throw IllegalArgumentException("الكتلة <$name> غير مغلقة — أضف </$name> في نهاية الكتلة")
                }
                entries += Entry.Block(name, blockLines, parseInnerDirectives(blockLines))
                i = j + 1
                continue
            }
            val stripped = stripTrailingComment(trimmed)
            if (stripped.isNotEmpty()) {
                entries += Entry.Directive.fromLine(stripped)
            }
            i++
        }
        return entries
    }

    /** Directive lines inside a `<connection>` block, normalised like top-level lines. */
    private fun parseInnerDirectives(blockLines: List<String>): List<Entry.Directive> {
        val directives = mutableListOf<Entry.Directive>()
        for (raw in blockLines.drop(1).dropLast(1)) {
            val trimmed = raw.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(";")) continue
            val stripped = stripTrailingComment(trimmed)
            if (stripped.isNotEmpty()) {
                directives += Entry.Directive.fromLine(stripped)
            }
        }
        return directives
    }

    /** Cuts a trailing comment: `#` or `;` preceded by a blank. */
    private fun stripTrailingComment(line: String): String {
        for (idx in line.indices) {
            val c = line[idx]
            if ((c == '#' || c == ';') && idx > 0 && (line[idx - 1] == ' ' || line[idx - 1] == '\t')) {
                return line.substring(0, idx).trimEnd()
            }
        }
        return line
    }

    private sealed interface Entry {
        data class Directive(val line: String, val keyword: String, val args: List<String>) : Entry {
            fun isSingleNonDirectiveToken(): Boolean {
                if (line.isBlank() || line.contains(' ') || line.contains('\t')) return false
                if (line.startsWith("<")) return false
                return keyword.lowercase() !in KNOWN_DIRECTIVES
            }

            companion object {
                fun fromLine(line: String): Directive {
                    val parts = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    return Directive(
                        line = line,
                        keyword = parts.firstOrNull().orEmpty(),
                        args = parts.drop(1),
                    )
                }
            }
        }

        data class Block(
            val name: String,
            val lines: List<String>,
            val innerDirectives: List<Directive>,
        ) : Entry
    }

    private data class Remote(val host: String, val port: Int, val proto: String?)

    private const val DEFAULT_PORT = 1194
    private const val DEFAULT_PROTO = "udp"
    private val BLOCK_OPEN_REGEX = Regex("^<([A-Za-z][A-Za-z0-9_-]*)>$")
    private val TUN_DEVICE_REGEX = Regex("tun[0-9]*")
    private val KNOWN_DIRECTIVES = setOf(
        "client", "remote", "proto", "dev", "dev-type", "cipher", "auth",
        "auth-user-pass", "tls-auth", "tls-crypt", "tls-crypt-v2", "ca",
        "cert", "key", "crl-verify", "pkcs12", "dh", "remote-cert-tls",
        "compress", "resolv-retry", "nobind", "redirect-gateway",
        "remote-random", "verb", "persist-key", "persist-tun", "float",
        "pull", "topology", "route", "route-delay", "dhcp-option",
        "script-security", "up", "down", "mute", "explicit-exit-notify",
        "connect-retry", "server-poll-timeout", "key-direction",
        "verify-x509-name", "tls-version-min", "auth-nocache",
        "reneg-sec", "inactive", "ping", "ping-restart", "ping-exit",
        "mssfix", "fragment", "tun-mtu", "link-mtu", "sndbuf", "rcvbuf",
        "user", "group", "chroot", "daemon", "log", "status", "ifconfig",
        "ifconfig-pool", "server", "push", "management", "plugin",
        "setenv", "ns-cert-type", "tls-remote", "http-proxy",
        "auth-user-pass-verify", "tls-verify", "route-up", "ipchange",
    )
}
