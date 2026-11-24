package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxyMode
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the per-app proxy choice (see [PerAppProxySettings]).
 *
 * A separate store — not an edit of [SettingsRepository] — so the new
 * screens add keys without touching the existing settings file layout:
 * both stores share the same `tunnel_settings` DataStore file, under
 * their own keys. The selected packages survive mode switches.
 */
interface PerAppProxyStore {
    val settings: Flow<PerAppProxySettings>

    suspend fun setMode(mode: PerAppProxyMode)

    suspend fun setPackages(packages: Set<String>)
}

@Singleton
class DataStorePerAppProxyStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : PerAppProxyStore {

    companion object {
        private val MODE_KEY = stringPreferencesKey("per_app_proxy_mode")
        private val PACKAGES_KEY = stringSetPreferencesKey("per_app_proxy_packages")
    }

    override val settings: Flow<PerAppProxySettings> = dataStore.data.map { prefs ->
        val mode = runCatching {
            PerAppProxyMode.valueOf(prefs[MODE_KEY] ?: PerAppProxyMode.ALL.name)
        }.getOrElse { PerAppProxyMode.ALL }
        PerAppProxySettings(
            mode = mode,
            packages = prefs[PACKAGES_KEY] ?: emptySet(),
        )
    }

    override suspend fun setMode(mode: PerAppProxyMode) {
        dataStore.edit { it[MODE_KEY] = mode.name }
    }

    override suspend fun setPackages(packages: Set<String>) {
        dataStore.edit { it[PACKAGES_KEY] = packages.toSet() }
    }
}
