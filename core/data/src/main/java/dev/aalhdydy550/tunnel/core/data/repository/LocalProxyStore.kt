package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import dev.aalhdydy550.tunnel.core.data.net.DEFAULT_LOCAL_PROXY_PORT
import dev.aalhdydy550.tunnel.core.data.net.LocalProxySettings
import dev.aalhdydy550.tunnel.core.data.net.validateLocalProxyPort
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the local proxy settings (see [LocalProxySettings]):
 * the port the tunnel's own proxy listens on, and whether other
 * devices on the LAN may connect through it.
 *
 * A separate store — not an edit of [SettingsRepository] — so the new
 * screen adds keys without touching the existing settings file layout:
 * both stores share the same `tunnel_settings` DataStore file, under
 * their own keys. The defaults (port 10808, LAN sharing off) keep the
 * current behaviour: the proxy listens on loopback only.
 */
interface LocalProxyStore {
    val settings: Flow<LocalProxySettings>

    /**
     * Saves [port] after validating it with [validateLocalProxyPort].
     * Returns false and saves nothing when the port is invalid
     * (out of range, or the reserved injector port 10810).
     */
    suspend fun setPort(port: Int): Boolean

    suspend fun setAllowOtherDevices(allow: Boolean)
}

@Singleton
class DataStoreLocalProxyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : LocalProxyStore {

    companion object {
        private val PORT_KEY = intPreferencesKey("local_proxy_port")
        private val ALLOW_OTHER_DEVICES_KEY =
            booleanPreferencesKey("local_proxy_allow_other_devices")
    }

    override val settings: Flow<LocalProxySettings> =
        dataStore.data.map { prefs ->
            LocalProxySettings(
                port = prefs[PORT_KEY] ?: DEFAULT_LOCAL_PROXY_PORT,
                allowOtherDevices = prefs[ALLOW_OTHER_DEVICES_KEY] ?: false,
            )
        }

    override suspend fun setPort(port: Int): Boolean {
        if (validateLocalProxyPort(port) != null) return false
        dataStore.edit { it[PORT_KEY] = port }
        return true
    }

    override suspend fun setAllowOtherDevices(allow: Boolean) {
        dataStore.edit { it[ALLOW_OTHER_DEVICES_KEY] = allow }
    }
}
