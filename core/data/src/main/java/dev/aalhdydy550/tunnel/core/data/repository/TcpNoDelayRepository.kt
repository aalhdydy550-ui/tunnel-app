package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TCP No Delay setting (§4-#18): disables Nagle's algorithm on the
 * tunnel's sockets, trading a few more small packets for lower
 * latency on interactive traffic.
 *
 * Opt-in, OFF by default: with the default value nothing in the app
 * changes behaviour — the setting only takes effect when the user
 * turns it on.
 *
 * A separate repository (not an edit to [SettingsRepository]) on the
 * shared DataStore instance, so the existing settings file is
 * untouched.
 */
@Singleton
class TcpNoDelayRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    companion object {
        private val TCP_NO_DELAY = booleanPreferencesKey("tcp_no_delay")

        /** The default: Nagle stays enabled, behaviour unchanged. */
        const val DEFAULT_ENABLED = false
    }

    val enabled: Flow<Boolean> =
        dataStore.data.map { it[TCP_NO_DELAY] ?: DEFAULT_ENABLED }

    suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[TCP_NO_DELAY] = enabled }
    }
}
