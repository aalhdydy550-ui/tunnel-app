package dev.aalhdydy550.tunnel.feature.connection.injector

import com.jcraft.jsch.SocketFactory
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Strict SNI-host validation, before any socket is opened.
 *
 * A valid SNI host is a DNS hostname: letters, digits, dots and
 * hyphens only, ≤ 253 characters, every label 1–63 characters and
 * not starting or ending with a hyphen, no empty labels, no spaces,
 * no IP address (v4 or v6) and no port. A single label (for example
 * `localhost`) is accepted as a hostname — the task's rule list does
 * not require a dot — but every real zero-rated host is an FQDN.
 *
 * Pure function, unit-tested on the JVM ([TlsSniValidationTest]).
 */
fun validateSniHost(host: String): Boolean {
    if (host.isBlank()) return false
    if (host.length > 253) return false
    // IPv6 literals and host:port forms are never valid SNI names.
    if (host.contains(':')) return false
    if (host.startsWith('.') || host.endsWith('.')) return false
    if (host.startsWith('-') || host.endsWith('-')) return false
    if (host.contains("..")) return false
    // ASCII letters/digits only: Char.isLetterOrDigit() would also
    // accept non-ASCII letters (for example 'ä'), which are illegal
    // in a DNS hostname / SNI value.
    if (!host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' }) return false
    val labels = host.split('.')
    for (label in labels) {
        if (label.isEmpty() || label.length > 63) return false
        if (label.startsWith('-') || label.endsWith('-')) return false
    }
    // An all-numeric dotted host is an IPv4 address, not a name.
    if (labels.all { label -> label.all { it in '0'..'9' } }) return false
    return true
}

/**
 * A [SocketFactory] for JSch that wraps the TCP connection to the
 * user's SSH server in TLS carrying a custom SNI value.
 *
 * The SNI host is the zero-rated host (for example `scorm.ekb.eg`):
 * the carrier sees a TLS handshake to that name, while the SSH
 * session runs inside the encrypted stream to the user's own server.
 *
 * **Deliberate, documented trade-off — permissive trust, injector
 * path only:** the [TrustManager] below accepts any server
 * certificate, because the server presents a certificate for itself,
 * not for the zero-rated SNI host, so normal chain/hostname
 * validation can never pass here. This factory must be used only
 * for the user's own SSH server (never for arbitrary hosts), and the
 * SSH layer itself still authenticates the server account with the
 * user's password. Do not reuse this trust manager anywhere else.
 */
class TlsSniSocketFactory(
    private val sniHost: String,
    private val tlsTimeoutMs: Int = InjectorConstants.TLS_CONNECT_TIMEOUT_MS,
) : SocketFactory {

    init {
        require(validateSniHost(sniHost)) { "Invalid SNI host: $sniHost" }
        require(tlsTimeoutMs > 0) { "TLS timeout must be positive" }
    }

    // One shared context: building an SSLContext per socket is wasteful
    // and the trust manager is stateless.
    private val sslContext: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(PermissiveTrustManager), SecureRandom())
        }
    }

    /**
     * Opens a TLS socket to ([host], [port]) with SNI = [sniHost].
     * The handshake runs here (not lazily) so TLS failures surface
     * during `Session.connect()` instead of mid-SSH-handshake.
     */
    @Throws(IOException::class, UnknownHostException::class)
    override fun createSocket(host: String, port: Int): Socket {
        val socket = sslContext.socketFactory.createSocket() as SSLSocket
        try {
            socket.soTimeout = tlsTimeoutMs
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(host, port), tlsTimeoutMs)
            socket.sslParameters = SSLParameters().apply {
                serverNames = listOf(SNIHostName(sniHost))
            }
            socket.startHandshake()
            return socket
        } catch (e: IOException) {
            runCatching { socket.close() }
            throw e
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()

    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()

    /**
     * Accepts any certificate. Injector path only — see the class doc.
     * Kept private so nothing else in the app can reach it.
     */
    private object PermissiveTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
