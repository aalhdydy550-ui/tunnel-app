package dev.aalhdydy550.tunnel.feature.connection

/**
 * Pure mapping from [TunnelState] to the Quick Settings tile UI.
 * Batch 1, item #2 (§4-#2 in the hamo-like-apps report).
 *
 * Kept free of any Android class (no `android.service.quicksettings`
 * here) so it is unit-testable on the JVM — see
 * `TunnelTileStateMapperTest`. The Android mapping (enum → `Tile`
 * state constant, label, icon) lives in [TunnelTileService].
 */
enum class TileUiState {
    /** Tunnel up: tile active. */
    CONNECTED,

    /** Tunnel down or failed: tile inactive, tap starts it. */
    DISCONNECTED,

    /** Starting / stopping / reconnecting: tile stays tappable so the
     * user can still stop a stuck start; the label says so. */
    CONNECTING,
}

object TunnelTileStateMapper {

    fun map(state: TunnelState): TileUiState = when (state) {
        is TunnelState.Started -> TileUiState.CONNECTED
        is TunnelState.Starting,
        is TunnelState.Stopping,
        is TunnelState.Reconnecting -> TileUiState.CONNECTING
        is TunnelState.Stopped,
        is TunnelState.Error -> TileUiState.DISCONNECTED
    }

    /** True while a tunnel is up or coming up — a tap must stop it. */
    fun isRunning(state: TunnelState): Boolean =
        map(state) != TileUiState.DISCONNECTED

    /** True only when the tunnel is fully down — a tap may start it. */
    fun shouldStart(state: TunnelState): Boolean =
        map(state) == TileUiState.DISCONNECTED
}
