package dev.aalhdydy550.tunnel.feature.connection.injector

import com.jcraft.jsch.ChannelDirectTCPIP
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A thin wrapper around a JSch SSH session for the injector path.
 *
 * `connect()` opens the session through [TlsSniSocketFactory] (TLS
 * with a custom SNI to the user's own SSH server) and then starts an
 * in-app SOCKS5 server ([InjectorSocksServer]) on
 * 127.0.0.1:[InjectorConstants.INJECTOR_SOCKS_PORT], which sing-box
 * consumes through its `socks` outbound (see [InjectorConfig]).
 * Each SOCKS CONNECT opens a JSch `direct-tcpip` channel on the
 * session — the equivalent of `ssh -D`, implemented here because
 * mwiede's JSch fork has no `Session.setPortForwardingD` (see
 * [InjectorSocksServer]).
 *
 * **Deliberate, documented trade-off — no host-key checking in v1:**
 * `StrictHostKeyChecking=no` disables verification of the server's
 * host-key fingerprint. This is acceptable only because the session
 * goes to the user's own server (entered by the user, not discovered)
 * and only over TLS to that same server; it is recorded here as a
 * known limitation, and a future version should pin the host key (or
 * store it on first use, TOFU) instead. Never use this client for
 * arbitrary hosts.
 *
 * All network work runs on [Dispatchers.IO]. No connection is ever
 * made from a unit test — the client is exercised only on the phone.
 */
class InjectorSshClient {

    private var session: Session? = null

    private var socksServer: InjectorSocksServer? = null

    /** True only while the underlying JSch session reports connected. */
    val isConnected: Boolean
        get() = session?.isConnected == true

    /**
     * Connects to ([host], [port]) as [user] with [password], inside
     * TLS carrying SNI = [sniHost], and starts the dynamic SOCKS
     * forward. Returns `Result.failure` (never throws) on any bad
     * input, TLS failure, authentication failure or forward failure —
     * the caller (the service) must not start the engine in that case.
     */
    suspend fun connect(
        host: String,
        port: Int,
        user: String,
        password: String,
        sniHost: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(validateSniHost(sniHost)) { "Invalid SNI host: $sniHost" }
            require(host.isNotBlank()) { "SSH host is blank" }
            require(port in 1..65535) { "SSH port out of range: $port" }
            require(user.isNotBlank()) { "SSH user is blank" }
            disconnect()
            val jsch = JSch()
            val newSession = jsch.getSession(user, host, port)
            newSession.setPassword(password)
            // v1 trade-off, see the class doc: no host-key verification
            // for the user's own server.
            newSession.setConfig("StrictHostKeyChecking", "no")
            newSession.setSocketFactory(TlsSniSocketFactory(sniHost))
            newSession.connect(InjectorConstants.SSH_CONNECT_TIMEOUT_MS)
            // SOCKS5 listener (the ssh -D equivalent): must start
            // after connect(), and binds 127.0.0.1:10810 — throws if
            // the port is already taken.
            val server = InjectorSocksServer(
                bindAddress = InjectorConstants.SOCKS_BIND_ADDRESS,
                port = InjectorConstants.INJECTOR_SOCKS_PORT,
                channelFactory = InjectorSocksServer.ChannelFactory { targetHost, targetPort ->
                    val channel = newSession.openChannel("direct-tcpip") as ChannelDirectTCPIP
                    channel.host = targetHost
                    channel.port = targetPort
                    channel.connect()
                    object : InjectorSocksServer.Channel {
                        override val input: InputStream = channel.inputStream
                        override val output: OutputStream = channel.outputStream
                        override fun close() {
                            runCatching { channel.disconnect() }
                        }
                    }
                },
            )
            try {
                server.start()
            } catch (e: Exception) {
                runCatching { newSession.disconnect() }
                throw e
            }
            session = newSession
            socksServer = server
        }
    }

    /** Disconnects the session and drops the SOCKS listener. Never throws. */
    fun disconnect() {
        runCatching { socksServer?.stop() }
        socksServer = null
        runCatching { session?.disconnect() }
        session = null
    }
}
