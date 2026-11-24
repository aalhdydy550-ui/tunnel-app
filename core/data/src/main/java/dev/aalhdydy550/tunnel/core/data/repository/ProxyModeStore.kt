package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * How the tunnel runs: the full-device VPN, or proxy-only mode
 * (item 5a) where sing-box only serves the local mixed proxy and no
 * traffic is routed automatically. The default is [VPN], so the
 * feature is OFF by construction until the user picks PROXY in
 * Settings — see `ProxyOnlyService` for what the mode changes.
 */
enum class ProxyMode {
    VPN,
    PROXY;

    companion object {
        /** Null or an unknown stored name falls back to [VPN]. */
        fun fromName(name: String?): ProxyMode = when (name) {
            PROXY.name -> PROXY
            else -> VPN
        }
    }
}

interface ProxyModeStore {
    val mode: Flow<ProxyMode>

    suspend fun setMode(mode: ProxyMode)
}

@Singleton
class DataStoreProxyModeStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : ProxyModeStore {

    companion object {
        private val MODE_KEY = stringPreferencesKey("proxy_mode")
    }

    override val mode: Flow<ProxyMode> =
        dataStore.data.map { prefs -> ProxyMode.fromName(prefs[MODE_KEY]) }

    override suspend fun setMode(mode: ProxyMode) {
        dataStore.edit { it[MODE_KEY] = mode.name }
    }
}
