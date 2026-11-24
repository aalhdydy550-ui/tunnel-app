package dev.aalhdydy550.tunnel.core.data.net

/**
 * Settings for the app's own local proxy (the tunnel exposes a local
 * proxy so other apps / devices on the LAN can route through it).
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 */

/** Default local proxy port. */
const val DEFAULT_LOCAL_PROXY_PORT = 10808

/** Lowest port a user may pick (below this are privileged ports). */
const val MIN_LOCAL_PROXY_PORT = 1024

/** Highest valid TCP port. */
const val MAX_LOCAL_PROXY_PORT = 65535

/**
 * Port reserved for the «نت مجاني» (free-data) injector path: the
 * in-app SOCKS5 server listens on it. Must equal
 * `InjectorConstants.INJECTOR_SOCKS_PORT` in
 * feature/connection/injector — kept in sync manually, because
 * core/data cannot import feature/connection.
 */
const val RESERVED_INJECTOR_SOCKS_PORT = 10810

data class LocalProxySettings(
    val port: Int = DEFAULT_LOCAL_PROXY_PORT,
    val allowOtherDevices: Boolean = false,
)

/**
 * Validates a user-typed local proxy port. Returns null when valid,
 * otherwise an Arabic error message for the UI.
 *
 * Rejected: anything outside 1024–65535, and the reserved injector
 * port 10810.
 */
fun validateLocalProxyPort(port: Int): String? {
    if (port !in MIN_LOCAL_PROXY_PORT..MAX_LOCAL_PROXY_PORT) {
        return "البورت يجب أن يكون بين 1024 و 65535."
    }
    if (port == RESERVED_INJECTOR_SOCKS_PORT) {
        return "البورت 10810 محجوز لمسار «نت مجاني» (SOCKS الداخلي) — اختر بورتًا آخر."
    }
    return null
}

/**
 * The address the local proxy binds to: all interfaces when other
 * devices on the LAN may connect, loopback only otherwise.
 */
fun localProxyListenAddress(allowOtherDevices: Boolean): String =
    if (allowOtherDevices) "0.0.0.0" else "127.0.0.1"
