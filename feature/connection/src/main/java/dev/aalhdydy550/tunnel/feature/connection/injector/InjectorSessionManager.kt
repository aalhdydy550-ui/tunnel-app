package dev.aalhdydy550.tunnel.feature.connection.injector

import com.jcraft.jsch.SocketFactory
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.HttpInjectSocketFactory
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.InjectorMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory holder for the live injector session.
 *
 * Holds exactly one [InjectorSshClient] plus the network id and SNI
 * host it was started with. **Nothing is persisted**: no password,
 * no SNI host and no SSH parameter is written to disk — everything
 * lives in RAM only, so a killed service cannot revive an injector
 * session (see `TunnelVpnService`: a saved injector session is a
 * marker that revives nothing). [stop] drops the client and clears
 * the fields; the password reference is released with the client.
 */
object InjectorSessionManager {

    /**
     * Parameters for one injector start. Carried in memory only.
     *
     * [mode] selects the transport: [InjectorMode.SNI] (the current
     * TLS path) or [InjectorMode.HTTP] (CONNECT handshake to the
     * carrier proxy). The HTTP fields ([proxyHost], [proxyPort],
     * [payloadText]) are used only in HTTP mode; the payload text is
     * the request built by
     * [dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadGenerator]
     * in the UI layer — this manager never builds payloads itself.
     */
    data class Params(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val sniHost: String,
        val networkId: Long,
        val mode: InjectorMode = InjectorMode.SNI,
        val proxyHost: String = "",
        val proxyPort: Int = 0,
        val payloadText: String = "",
        val tcpNoDelay: Boolean = false,
    )

    private val mutex = Mutex()

    @Volatile
    private var client: InjectorSshClient? = null

    @Volatile
    private var _currentNetworkId: Long? = null

    @Volatile
    private var _currentSniHost: String? = null

    @Volatile
    private var _currentMode: InjectorMode? = null

    /** True only while the underlying JSch session reports connected. */
    val isLive: Boolean
        get() = client?.isConnected == true

    /** Network id the live session belongs to, or null when stopped. */
    val currentNetworkId: Long?
        get() = _currentNetworkId

    /** SNI host the live session uses, or null when stopped. */
    val currentSniHost: String?
        get() = _currentSniHost

    /** Transport mode of the live session, or null when stopped. */
    val currentMode: InjectorMode?
        get() = _currentMode

    /**
     * Starts the injector session (stopping any previous one first).
     * On failure the previous state is already gone and nothing is
     * left half-connected — the caller must not start the engine.
     */
    suspend fun start(params: Params): Result<Unit> = mutex.withLock {
        stopLocked()
        // Bad HTTP parameters fail here as a Result, never as a throw:
        // the service treats start() as non-throwing (see
        // TunnelVpnService.startTunnel) and reports the failure.
        val factory = try {
            socketFactoryFor(params)
        } catch (e: Exception) {
            return@withLock Result.failure(e)
        }
        val newClient = InjectorSshClient()
        newClient.connect(
            host = params.host,
            port = params.port,
            user = params.user,
            password = params.password,
            sniHost = params.sniHost,
            socketFactory = factory,
            tcpNoDelay = params.tcpNoDelay,
        ).onSuccess {
            client = newClient
            _currentNetworkId = params.networkId
            _currentSniHost = params.sniHost
            _currentMode = params.mode
        }.onFailure {
            newClient.disconnect()
        }
    }

    /**
     * The transport for one start. SNI (the default) keeps the
     * current TLS factory; HTTP builds the inject factory from the
     * user-entered proxy and the payload text the UI built. Bad HTTP
     * parameters fail here, before any socket is opened.
     */
    private fun socketFactoryFor(params: Params): SocketFactory? = when (params.mode) {
        InjectorMode.SNI -> null
        InjectorMode.HTTP -> {
            require(params.proxyHost.isNotBlank()) { "Proxy host is blank" }
            require(params.proxyPort in 1..65535) {
                "Proxy port out of range: ${params.proxyPort}"
            }
            require(params.payloadText.isNotBlank()) { "Payload is blank" }
            HttpInjectSocketFactory(
                proxyHost = params.proxyHost,
                proxyPort = params.proxyPort,
                payload = params.payloadText.toByteArray(Charsets.UTF_8),
            )
        }
    }

    /** Stops the live session, if any, and clears all held state. */
    suspend fun stop() = mutex.withLock { stopLocked() }

    private fun stopLocked() {
        client?.disconnect()
        client = null
        _currentNetworkId = null
        _currentSniHost = null
        _currentMode = null
    }
}
