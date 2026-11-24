package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.aalhdydy550.tunnel.core.data.net.DnsPreference
import dev.aalhdydy550.tunnel.core.data.net.DnsPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the DNS choice (see [DnsPreference]).
 *
 * A separate store — not an edit of [SettingsRepository] — so the new
 * screen adds keys without touching the existing settings file layout:
 * both stores share the same `tunnel_settings` DataStore file, under
 * their own keys. The default is [DnsPreset.AUTO], which keeps the
 * engine's current behaviour (probe the fastest resolver).
 */
interface DnsPreferenceStore {
    val preference: Flow<DnsPreference>

    suspend fun setPreference(preference: DnsPreference)
}

@Singleton
class DataStoreDnsPreferenceStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : DnsPreferenceStore {

    companion object {
        private val PRESET_KEY = stringPreferencesKey("dns_preset")
        private val CUSTOM_HOST_KEY = stringPreferencesKey("dns_custom_host")
    }

    override val preference: Flow<DnsPreference> = dataStore.data.map { prefs ->
        val preset = runCatching {
            DnsPreset.valueOf(prefs[PRESET_KEY] ?: DnsPreset.AUTO.name)
        }.getOrElse { DnsPreset.AUTO }
        DnsPreference(
            preset = preset,
            customHost = prefs[CUSTOM_HOST_KEY] ?: "",
        )
    }

    override suspend fun setPreference(preference: DnsPreference) {
        dataStore.edit {
            it[PRESET_KEY] = preference.preset.name
            it[CUSTOM_HOST_KEY] = preference.customHost.trim()
        }
    }
}
