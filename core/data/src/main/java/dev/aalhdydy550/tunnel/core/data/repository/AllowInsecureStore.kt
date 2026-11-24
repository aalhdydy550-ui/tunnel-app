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
 * "Allow insecure" setting: permits connections to servers with
 * untrusted / self-signed TLS certificates (Hamo-compatible option).
 *
 * Opt-in, OFF by default: with the default value nothing in the app
 * changes behaviour — the setting only takes effect when the user
 * turns it on.
 *
 * A separate store (not an edit to [SettingsRepository]) on the
 * shared DataStore instance, so the existing settings file is
 * untouched. The interface exists (rather than a concrete class
 * like [TcpNoDelayRepository]) so other modules can fake it in
 * their ViewModel tests without a DataStore on the classpath.
 */
interface AllowInsecureStore {
    val enabled: Flow<Boolean>

    suspend fun setEnabled(enabled: Boolean)
}

@Singleton
class DataStoreAllowInsecureRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) : AllowInsecureStore {

    companion object {
        private val ALLOW_INSECURE = booleanPreferencesKey("allow_insecure")

        /** The default: insecure connections stay rejected. */
        const val DEFAULT_ENABLED = false
    }

    override val enabled: Flow<Boolean> =
        dataStore.data.map { it[ALLOW_INSECURE] ?: DEFAULT_ENABLED }

    override suspend fun setEnabled(enabled: Boolean) {
        dataStore.edit { it[ALLOW_INSECURE] = enabled }
    }
}
