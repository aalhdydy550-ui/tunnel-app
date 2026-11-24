package dev.aalhdydy550.tunnel.feature.connection.injector.payload

import com.jcraft.jsch.SocketFactory
import dev.aalhdydy550.tunnel.feature.connection.injector.InjectorConstants
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** Transport mode of one injector session: SNI (current) or HTTP inject. */
enum class InjectorMode {
    SNI,
    HTTP,
}

/**
 * HTTP injection transport: writes a built payload to a plain TCP
 * socket (opened to the carrier proxy) and reads the proxy's
 * response, then hands the raw socket to JSch — the same pattern as
 * [dev.aalhdydy550.tunnel.feature.connection.injector.TlsSniSocketFactory],
 * with the TLS step replaced by the HTTP handshake.
 *
 * Flow: phone → TCP to the carrier proxy (user-entered) → payload
 * (`CONNECT <ssh-host>:<port>` + `Host:` / `X-Online-Host:`) → proxy
 * answers `200` → the socket becomes a raw tunnel → SSH runs inside
 * it (JSch) → dynamic SOCKS forward on 127.0.0.1:10810 → sing-box.
 *
 * **No read-ahead past the headers.** [readHeaders] reads byte by
 * byte until the blank line ending the headers. A buffered reader
 * would swallow bytes past the headers into its own buffer — and the
 * SSH server's version banner, which arrives right after the `200`,
 * would be lost before JSch ever sees the socket. The handshake is
 * a few hundred bytes, so the byte-wise read costs nothing.
 *
 * On success the socket is left open (JSch owns it from here). On
 * any failure the socket is closed and the error carries an Arabic
 * message — the caller must not start the engine (same rule as the
 * SNI path: no engine without a live SSH session).
 *
 * Pure `java.net` + JSch's [SocketFactory] interface; no new
 * dependency. Unit-tested on the JVM against [dev.aalhdydy550.tunnel.feature.connection.FakeHttpServer]
 * and a raw stub proxy.
 */
class HttpInjector(
    private val timeoutMs: Int = InjectorConstants.HTTP_INJECT_TIMEOUT_MS,
) {

    /**
     * Writes [payload] to the (already connected) [socket] and reads
     * the proxy response. Succeeds only on HTTP `200` (or `101`).
     * The socket stays open on success; it is closed on failure.
     */
    fun inject(socket: Socket, payload: ByteArray): Result<Unit> {
        if (payload.isEmpty()) {
            return Result.failure(
                IllegalArgumentException("الحمولة فارغة — لا يمكن الحقن"),
            )
        }
        return runCatching {
            try {
                socket.soTimeout = timeoutMs
                socket.getOutputStream().apply {
                    write(payload)
                    flush()
                }
                val headers = readHeaders(socket.getInputStream())
                val code = parseStatusCode(headers)
                    ?: throw IOException("رد غير مفهوم من البروكسي")
                if (code != 200 && code != 101) {
                    throw IOException("البروكسي رفض الاتصال (HTTP $code)")
                }
            } catch (e: SocketTimeoutException) {
                throw IOException("انتهت مهلة انتظار رد البروكسي", e)
            }
        }.onFailure {
            runCatching { socket.close() }
        }
    }

    companion object {
        /** Longest header block accepted before giving up. */
        const val MAX_HEADER_BYTES = 8192

        /**
         * Reads response headers byte by byte until the blank line
         * (`\r\n\r\n` or `\n\n`) and returns the raw header block.
         * Never reads past the terminator. Throws [IOException] on
         * EOF, on an over-long block, or on a read timeout.
         */
        @Throws(IOException::class)
        fun readHeaders(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) {
                val byte = input.read()
                if (byte == -1) {
                    throw IOException("أغلق البروكسي الاتصال قبل اكتمال الرد")
                }
                out.write(byte)
                if (out.size() > MAX_HEADER_BYTES) {
                    throw IOException("ترويسات البروكسي أطول من المتوقع")
                }
                if (byte == '\n'.code) {
                    val text = out.toString(Charsets.US_ASCII.name())
                    if (text.endsWith("\r\n\r\n") || text.endsWith("\n\n")) {
                        return text
                    }
                }
            }
        }

        /**
         * Status code from a response header block (first line
         * `HTTP/1.1 200 ...`), or null when the line is malformed.
         */
        fun parseStatusCode(headerBlock: String): Int? {
            val statusLine = headerBlock.lineSequence().firstOrNull().orEmpty().trim()
            val parts = statusLine.split(" ")
            if (parts.size < 2) return null
            if (!parts[0].startsWith("HTTP/")) return null
            return parts[1].toIntOrNull()
        }

        /**
         * Parses a user-entered proxy address: `host` (port defaults
         * to 80) or `host:port`. Returns null on blank input, spaces,
         * a non-numeric port or an out-of-range port. IPv6 literals
         * use brackets (`[::1]:8080`); a bare `::1` without brackets
         * is rejected as ambiguous.
         */
        fun parseProxyAddress(text: String): Pair<String, Int>? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it == ' ' || it == '\t' || it == '\r' || it == '\n' }) {
                return null
            }
            if (trimmed.startsWith("[")) {
                val close = trimmed.indexOf(']')
                if (close < 0) return null
                val host = trimmed.substring(1, close)
                if (host.isBlank()) return null
                val rest = trimmed.substring(close + 1)
                if (rest.isEmpty()) return host to 80
                if (!rest.startsWith(":")) return null
                val port = rest.substring(1).toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                return host to port
            }
            val colons = trimmed.count { it == ':' }
            if (colons == 0) return trimmed to 80
            if (colons == 1) {
                val (host, portText) = trimmed.split(":")
                if (host.isBlank()) return null
                val port = portText.toIntOrNull() ?: return null
                if (port !in 1..65535) return null
                return host to port
            }
            return null
        }
    }
}

/**
 * A JSch [SocketFactory] that performs the HTTP injection handshake
 * before handing the socket to JSch.
 *
 * `createSocket(host, port)` is called by JSch with the SSH server's
 * address — it is intentionally ignored here: the TCP connection
 * goes to the carrier proxy ([proxyHost]:[proxyPort]), and the
 * payload's `CONNECT` line names the SSH server. The socket timeout
 * is left set after the handshake, exactly as
 * `TlsSniSocketFactory` does (its 10 s TLS timeout is left set too).
 */
class HttpInjectSocketFactory(
    private val proxyHost: String,
    private val proxyPort: Int,
    private val payload: ByteArray,
    private val timeoutMs: Int = InjectorConstants.HTTP_INJECT_TIMEOUT_MS,
) : SocketFactory {

    init {
        require(proxyHost.isNotBlank()) { "Proxy host is blank" }
        require(proxyPort in 1..65535) { "Proxy port out of range: $proxyPort" }
        require(payload.isNotEmpty()) { "Payload is empty" }
        require(timeoutMs > 0) { "Timeout must be positive" }
    }

    @Throws(IOException::class)
    override fun createSocket(host: String, port: Int): Socket {
        val socket = Socket()
        try {
            socket.soTimeout = timeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            HttpInjector(timeoutMs).inject(socket, payload).getOrThrow()
            return socket
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw IOException("HTTP inject failed: ${e.message}", e)
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): java.io.OutputStream =
        socket.getOutputStream()
}
