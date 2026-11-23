package dev.aalhdydy550.tunnel.feature.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private val Context.bootSettingsDataStore by preferencesDataStore(name = "tunnel_settings")

/**
 * Auto-restart on boot (opt-in setting).
 *
 * When the user enabled "start on boot", the last saved session is
 * restored after a reboot — the tunnel comes back by itself. The VPN
 * permission survives a reboot (it is a system grant), and the service
 * still verifies it in `openTun` before establishing the interface.
 * A deliberate user stop clears the saved session, so a stopped tunnel
 * never restarts itself.
 *
 * Starting a foreground service from BOOT_COMPLETED is one of the
 * cases Android explicitly allows (no background-start restriction).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        try {
            val autoStart = runBlocking {
                context.bootSettingsDataStore.data.first()[
                    booleanPreferencesKey("auto_start_on_boot"),
                ] ?: false
            }
            if (!autoStart) return
            val session = LastSessionStore(context).load() ?: return
            Log.i(TAG, "boot: restoring last session (${session.profileName})")
            ContextCompat.startForegroundService(
                context,
                TunnelVpnService.startIntent(
                    context,
                    session.profileId,
                    session.profileName,
                    session.profileType,
                    session.configJson,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "boot restore failed", e)
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
