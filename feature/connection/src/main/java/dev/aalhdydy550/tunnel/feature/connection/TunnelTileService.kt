package dev.aalhdydy550.tunnel.feature.connection

import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Quick Settings tile: one tap toggles the tunnel from any screen.
 * Batch 1, item #2 (§4-#2 in the hamo-like-apps report) — the pattern
 * every open-source sing-box/V2Ray client ships (SFA, v2rayNG);
 * studied, not copied.
 *
 * Read-only with respect to the tunnel: it never touches
 * `TunnelVpnService` internals. It only
 * - reads the shared [TunnelStateHolder.state] (written by the
 *   service), and
 * - sends the service's own public intents (`ACTION_START` /
 *   `ACTION_STOP` via `startIntent` / `stopIntent`).
 *
 * Behaviour:
 * - Tap while running (started / starting / reconnecting) → stop.
 * - Tap while stopped → restart the last session from
 *   [LastSessionStore] (same document the service itself revives
 *   after a system kill). No saved session, an injector session
 *   (SSH credentials are memory-only and cannot be revived), or a
 *   never-granted VPN permission → open the app instead, so the user
 *   picks a profile or grants the permission there. The tile cannot
 *   show the system VPN-permission dialog itself.
 * - While the QS panel is open the tile follows the live state: the
 *   flow is collected only between `onStartListening` and
 *   `onStopListening`, so there is no background work, no polling,
 *   and no battery cost when the panel is closed.
 *
 * Declared in `feature/connection/src/main/AndroidManifest.xml`
 * (BIND_QUICK_SETTINGS_TILE + QS_TILE intent filter). The user adds
 * it from the QS panel editor; no code can add a tile by itself.
 */
class TunnelTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTile(TunnelStateHolder.state.value)
        collectJob = scope.launch {
            TunnelStateHolder.state.collect { updateTile(it) }
        }
    }

    override fun onStopListening() {
        collectJob?.cancel()
        collectJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (TunnelTileStateMapper.isRunning(TunnelStateHolder.state.value)) {
            ContextCompat.startForegroundService(
                this,
                TunnelVpnService.stopIntent(this),
            )
        } else {
            startLastSessionOrOpenApp()
        }
        // The collector above refreshes the tile as the service
        // reports the new state while the panel stays open.
    }

    private fun updateTile(state: TunnelState) {
        val tile = qsTile ?: return
        when (TunnelTileStateMapper.map(state)) {
            TileUiState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = "متصل — ${(state as TunnelState.Started).profileName}"
            }
            TileUiState.CONNECTING -> {
                // ACTIVE (not UNAVAILABLE) on purpose: UNAVAILABLE
                // greys the tile out and swallows taps, so the user
                // could not stop a stuck start. The label says
                // "connecting", the tap still stops.
                tile.state = Tile.STATE_ACTIVE
                tile.label = "جارٍ الاتصال…"
            }
            TileUiState.DISCONNECTED -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = "غير متصل"
            }
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.updateTile()
    }

    private fun startLastSessionOrOpenApp() {
        val session = LastSessionStore(this).load()
        val canStart = session != null &&
            !session.isInjector &&
            VpnService.prepare(this) == null
        if (canStart && session != null) {
            ContextCompat.startForegroundService(
                this,
                TunnelVpnService.startIntent(
                    context = this,
                    profileId = session.profileId,
                    profileName = session.profileName,
                    profileType = session.profileType,
                    configJson = session.configJson,
                ),
            )
            return
        }
        // No saved session, an injector session (its SSH credentials
        // live in memory only — see TunnelVpnService), or the VPN
        // permission was never granted: open the app (unlocking first
        // if the device is locked) so the user picks a profile or
        // grants the permission there.
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        unlockAndRun { startActivity(launch) }
    }
}
