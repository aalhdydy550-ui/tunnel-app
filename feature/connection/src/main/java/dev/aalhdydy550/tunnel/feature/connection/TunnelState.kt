package dev.aalhdydy550.tunnel.feature.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process tunnel state, observed by the UI. */
sealed interface TunnelState {
    data object Stopped : TunnelState
    data class Starting(val profileName: String) : TunnelState
    data class Started(val profileName: String) : TunnelState
    data class Stopping(val profileName: String) : TunnelState

    /**
     * Automatic reconnect in progress: the tunnel dropped (network
     * change, server timeout, or silent death caught by the heartbeat)
     * and the service is retrying with backoff. [attempt] is 1-based,
     * [lastReason] is the human-readable cause of the last failure.
     */
    data class Reconnecting(
        val profileName: String,
        val attempt: Int,
        val lastReason: String,
    ) : TunnelState

    data class Error(val message: String) : TunnelState
}

/**
 * Single shared holder for the tunnel state. The service (same process in
 * v0.1) writes it; the UI collects it. If the service ever moves to its
 * own process, this must become a bound-service or broadcast channel.
 */
object TunnelStateHolder {
    private val _state = MutableStateFlow<TunnelState>(TunnelState.Stopped)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    fun update(state: TunnelState) {
        _state.value = state
    }
}
