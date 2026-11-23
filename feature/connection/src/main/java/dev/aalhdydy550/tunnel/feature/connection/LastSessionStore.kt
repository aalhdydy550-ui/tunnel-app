package dev.aalhdydy550.tunnel.feature.connection

import android.content.Context
import android.content.SharedPreferences

/**
 * The last running session, persisted so the service can revive itself:
 *
 * - after the system kills the service (START_STICKY restart with a
 *   `null` intent), and
 * - after a device reboot, when the user enabled auto-start on boot.
 *
 * Only the sing-box configuration document + the profile name/id are
 * stored — no passwords beyond what the configuration itself already
 * contains (the same document the engine already runs). [clear] is
 * called on every deliberate user stop, so a stopped tunnel never
 * restarts itself.
 *
 * SharedPreferences (not DataStore) on purpose: the service and the
 * boot receiver need a synchronous read on a cold start.
 */
class LastSessionStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Session(
        val profileId: Long,
        val profileName: String,
        val profileType: String,
        val configJson: String,
    )

    fun save(profileId: Long, profileName: String, profileType: String, configJson: String) {
        prefs.edit()
            .putLong(KEY_PROFILE_ID, profileId)
            .putString(KEY_PROFILE_NAME, profileName)
            .putString(KEY_PROFILE_TYPE, profileType)
            .putString(KEY_CONFIG_JSON, configJson)
            .apply()
    }

    fun load(): Session? {
        val configJson = prefs.getString(KEY_CONFIG_JSON, null) ?: return null
        val profileName = prefs.getString(KEY_PROFILE_NAME, null) ?: return null
        return Session(
            profileId = prefs.getLong(KEY_PROFILE_ID, -1L),
            profileName = profileName,
            profileType = prefs.getString(KEY_PROFILE_TYPE, "VLESS") ?: "VLESS",
            configJson = configJson,
        )
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "last_tunnel_session"
        private const val KEY_PROFILE_ID = "profile_id"
        private const val KEY_PROFILE_NAME = "profile_name"
        private const val KEY_PROFILE_TYPE = "profile_type"
        private const val KEY_CONFIG_JSON = "config_json"
    }
}
