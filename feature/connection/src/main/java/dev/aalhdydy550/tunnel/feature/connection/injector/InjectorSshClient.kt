package dev.aalhdydy550.tunnel.feature.connection.injector

import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A thin wrapper around a JSch SSH session for the injector path.
 *
 * `connect()` opens the session through [TlsSniSocketFactory] (TLS
 * with a custom SNI to the user's own SSH server) and then installs
 * a dynamic (SOCKS5) port forward on
 * 127.0.0.1:[InjectorConstants.INJECTOR_SOCKS_PORT], which sing-box
 * consumes through its `socks` outbound (see [InjectorConfig]).
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
            try {
                // Dynamic forward (SOCKS): must run after connect().
                // Returns the bound port; throws if 10810 is taken.
                newSession.setPortForwardingD(
                    InjectorConstants.SOCKS_BIND_ADDRESS,
                    InjectorConstants.INJECTOR_SOCKS_PORT,
                )
            } catch (e: Exception) {
                runCatching { newSession.disconnect() }
                throw e
            }
            session = newSession
        }
    }

    /** Disconnects the session and drops the SOCKS listener. Never throws. */
    fun disconnect() {
        runCatching { session?.disconnect() }
        session = null
    }
}
