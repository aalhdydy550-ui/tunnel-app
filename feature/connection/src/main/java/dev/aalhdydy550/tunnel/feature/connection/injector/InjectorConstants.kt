package dev.aalhdydy550.tunnel.feature.connection.injector

/**
 * Fixed constants for the «نت مجاني» (free-data) injector path:
 * a lightweight in-app SSH client (JSch) wrapped in TLS with a custom
 * SNI, exposing a dynamic (SOCKS5) forward on loopback for sing-box.
 *
 * Chain: phone → TCP to the user's own SSH server (profile port,
 * usually 443 with stunnel on the server) → TLS with SNI = the
 * zero-rated host → SSH inside TLS → dynamic SOCKS forward on
 * 127.0.0.1:10810 → sing-box (`socks` outbound) → TUN.
 */
object InjectorConstants {
    /** Local SOCKS5 port the JSch dynamic forward listens on. */
    const val INJECTOR_SOCKS_PORT = 10810

    /** Loopback address the dynamic forward binds to (never 0.0.0.0). */
    const val SOCKS_BIND_ADDRESS = "127.0.0.1"

    /** How long `Session.connect()` may take (TCP + TLS + SSH handshake). */
    const val SSH_CONNECT_TIMEOUT_MS = 15_000

    /** How long the TLS socket connect + handshake may take. */
    const val TLS_CONNECT_TIMEOUT_MS = 10_000
}
