package dev.aalhdydy550.tunnel.feature.connection.injector

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

    /** Parameters for one injector start. Carried in memory only. */
    data class Params(
        val host: String,
        val port: Int,
        val user: String,
        val password: String,
        val sniHost: String,
        val networkId: Long,
    )

    private val mutex = Mutex()

    @Volatile
    private var client: InjectorSshClient? = null

    @Volatile
    private var _currentNetworkId: Long? = null

    @Volatile
    private var _currentSniHost: String? = null

    /** True only while the underlying JSch session reports connected. */
    val isLive: Boolean
        get() = client?.isConnected == true

    /** Network id the live session belongs to, or null when stopped. */
    val currentNetworkId: Long?
        get() = _currentNetworkId

    /** SNI host the live session uses, or null when stopped. */
    val currentSniHost: String?
        get() = _currentSniHost

    /**
     * Starts the injector session (stopping any previous one first).
     * On failure the previous state is already gone and nothing is
     * left half-connected — the caller must not start the engine.
     */
    suspend fun start(params: Params): Result<Unit> = mutex.withLock {
        stopLocked()
        val newClient = InjectorSshClient()
        newClient.connect(
            host = params.host,
            port = params.port,
            user = params.user,
            password = params.password,
            sniHost = params.sniHost,
        ).onSuccess {
            client = newClient
            _currentNetworkId = params.networkId
            _currentSniHost = params.sniHost
        }.onFailure {
            newClient.disconnect()
        }
    }

    /** Stops the live session, if any, and clears all held state. */
    suspend fun stop() = mutex.withLock { stopLocked() }

    private fun stopLocked() {
        client?.disconnect()
        client = null
        _currentNetworkId = null
        _currentSniHost = null
    }
}
