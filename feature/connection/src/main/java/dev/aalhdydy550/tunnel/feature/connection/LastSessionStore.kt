package dev.aalhdydy550.tunnel.feature.connection

import android.content.Context
import android.content.SharedPreferences
import dev.aalhdydy550.tunnel.core.data.net.DEFAULT_LOCAL_PROXY_PORT

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
 * The session also records its kind: a proxy-only session
 * ([Session.isProxy] + [Session.proxyPort]) is revived by
 * [ProxyOnlyService], not by the VPN service — reviving it through
 * the VPN service would ask for the VPN permission for nothing and
 * break the meaning of the mode. Sessions saved before this field
 * existed load as VPN sessions (the old default).
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
        /**
         * True when this session is an injector session (JSch + SNI).
         * A saved injector session is only a marker: the SSH
         * credentials live in memory only, so the service must NOT
         * revive it after a system kill — see `TunnelVpnService`.
         */
        val isInjector: Boolean = false,
        /**
         * True when this session ran in proxy-only mode (no VPN
         * interface). [proxyPort] is the local proxy port the
         * session listened on. Both default to the old behaviour
         * (a VPN session on [DEFAULT_LOCAL_PROXY_PORT]) so sessions
         * saved before these fields existed still load.
         */
        val isProxy: Boolean = false,
        val proxyPort: Int = DEFAULT_LOCAL_PROXY_PORT,
    )

    fun save(
        profileId: Long,
        profileName: String,
        profileType: String,
        configJson: String,
        isInjector: Boolean = false,
        isProxy: Boolean = false,
        proxyPort: Int = DEFAULT_LOCAL_PROXY_PORT,
    ) {
        prefs.edit()
            .putLong(KEY_PROFILE_ID, profileId)
            .putString(KEY_PROFILE_NAME, profileName)
            .putString(KEY_PROFILE_TYPE, profileType)
            .putString(KEY_CONFIG_JSON, configJson)
            .putBoolean(KEY_IS_INJECTOR, isInjector)
            .putBoolean(KEY_IS_PROXY, isProxy)
            .putInt(KEY_PROXY_PORT, proxyPort)
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
            isInjector = prefs.getBoolean(KEY_IS_INJECTOR, false),
            isProxy = prefs.getBoolean(KEY_IS_PROXY, false),
            proxyPort = prefs.getInt(KEY_PROXY_PORT, DEFAULT_LOCAL_PROXY_PORT),
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
        private const val KEY_IS_INJECTOR = "is_injector"
        private const val KEY_IS_PROXY = "is_proxy"
        private const val KEY_PROXY_PORT = "proxy_port"
    }
}
