package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Small user settings, stored in DataStore Preferences.
 * The selected profile id lives here (not in Room) so switching servers
 * never touches the profile table.
 */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val SELECTED_PROFILE_ID = longPreferencesKey("selected_profile_id")
        private val AUTO_ROUTE = booleanPreferencesKey("auto_route")
        private val STRICT_ROUTE = booleanPreferencesKey("strict_route")
        private val AUTO_START_ON_BOOT = booleanPreferencesKey("auto_start_on_boot")
        private val KILL_SWITCH_GUIDED = booleanPreferencesKey("kill_switch_guided")
        private val ADS_BLOCKING = booleanPreferencesKey("ads_blocking")
    }

    /** -1 means "no profile selected". */
    val selectedProfileId: Flow<Long> = dataStore.data.map { it[SELECTED_PROFILE_ID] ?: -1L }

    val autoRoute: Flow<Boolean> = dataStore.data.map { it[AUTO_ROUTE] ?: true }

    val strictRoute: Flow<Boolean> = dataStore.data.map { it[STRICT_ROUTE] ?: true }

    /**
     * Reconnect the last session automatically after a device reboot.
     * Off by default: the user opts in from the servers screen.
     */
    val autoStartOnBoot: Flow<Boolean> = dataStore.data.map { it[AUTO_START_ON_BOOT] ?: false }

    /**
     * The user has been shown the kill-switch guidance (Android's
     * Always-on VPN + "Block connections without VPN") and opened the
     * system VPN settings from it. The enforcement itself is the
     * operating system's — the app cannot toggle it programmatically.
     */
    val killSwitchGuided: Flow<Boolean> = dataStore.data.map { it[KILL_SWITCH_GUIDED] ?: false }

    /**
     * Ad blocking via the downloaded geosite rule-set. Off by default:
     * the rule needs the downloaded data file, and the user opts in.
     * When the file is missing the rule is omitted (safe fallback) and
     * the UI shows the stale-data notice.
     */
    val adsBlocking: Flow<Boolean> = dataStore.data.map { it[ADS_BLOCKING] ?: false }

    suspend fun setSelectedProfileId(id: Long) {
        dataStore.edit { it[SELECTED_PROFILE_ID] = id }
    }

    suspend fun setAutoRoute(enabled: Boolean) {
        dataStore.edit { it[AUTO_ROUTE] = enabled }
    }

    suspend fun setStrictRoute(enabled: Boolean) {
        dataStore.edit { it[STRICT_ROUTE] = enabled }
    }

    suspend fun setAutoStartOnBoot(enabled: Boolean) {
        dataStore.edit { it[AUTO_START_ON_BOOT] = enabled }
    }

    suspend fun setKillSwitchGuided(done: Boolean) {
        dataStore.edit { it[KILL_SWITCH_GUIDED] = done }
    }

    suspend fun setAdsBlocking(enabled: Boolean) {
        dataStore.edit { it[ADS_BLOCKING] = enabled }
    }
}
