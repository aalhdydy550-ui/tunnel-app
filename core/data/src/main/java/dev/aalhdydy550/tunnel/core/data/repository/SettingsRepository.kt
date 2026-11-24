package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.aalhdydy550.tunnel.core.data.profile.DefaultSubscription
import dev.aalhdydy550.tunnel.core.data.profile.SubscriptionFetcher
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
        private val DEFAULT_SUBSCRIPTION_URL =
            stringPreferencesKey("default_subscription_url")
        private val DEFAULT_SUBSCRIPTION_ID =
            longPreferencesKey("default_subscription_id")
        private val DEFAULT_SUBSCRIPTION_ENABLED =
            booleanPreferencesKey("default_subscription_enabled")
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

    /**
     * The default free subscription's URL. The factory value is
     * [DefaultSubscription.DEFAULT_URL]; the user replaces it from
     * the import screen — it is a setting, never hard-coded behaviour.
     */
    val defaultSubscriptionUrl: Flow<String> = dataStore.data.map {
        it[DEFAULT_SUBSCRIPTION_URL] ?: DefaultSubscription.DEFAULT_URL
    }

    /** The row id of the seeded default subscription, -1 when none. */
    val defaultSubscriptionId: Flow<Long> = dataStore.data.map {
        it[DEFAULT_SUBSCRIPTION_ID] ?: -1L
    }

    /**
     * Whether the default free subscription is wanted at all. On by
     * default; deleting the default subscription from the import
     * screen turns it off, so the seeder never silently re-adds a
     * subscription the user removed.
     */
    val defaultSubscriptionEnabled: Flow<Boolean> = dataStore.data.map {
        it[DEFAULT_SUBSCRIPTION_ENABLED] ?: true
    }

    /** Validates (https, or loopback http for tests) and stores the URL. */
    suspend fun setDefaultSubscriptionUrl(url: String) {
        val cleanUrl = url.trim()
        require(SubscriptionFetcher.isAllowedSubscriptionUrl(cleanUrl)) {
            "subscription URL must use https:// (plain http:// is allowed only for loopback hosts)"
        }
        dataStore.edit { it[DEFAULT_SUBSCRIPTION_URL] = cleanUrl }
    }

    suspend fun setDefaultSubscriptionId(id: Long) {
        dataStore.edit { it[DEFAULT_SUBSCRIPTION_ID] = id }
    }

    suspend fun setDefaultSubscriptionEnabled(enabled: Boolean) {
        dataStore.edit { it[DEFAULT_SUBSCRIPTION_ENABLED] = enabled }
    }
}
