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
    /** Local SOCKS5 port the in-app SOCKS server listens on. */
    const val INJECTOR_SOCKS_PORT = 10810

    /** Loopback address the dynamic forward binds to (never 0.0.0.0). */
    const val SOCKS_BIND_ADDRESS = "127.0.0.1"

    /** How long `Session.connect()` may take (TCP + TLS + SSH handshake). */
    const val SSH_CONNECT_TIMEOUT_MS = 15_000

    /** How long the TLS socket connect + handshake may take. */
    const val TLS_CONNECT_TIMEOUT_MS = 10_000

    // ------------------------------------------------- HTTP inject (item 3)

    /** How long the HTTP inject handshake may take (TCP + write + read). */
    const val HTTP_INJECT_TIMEOUT_MS = 15_000

    /** Carrier-proxy ports tried by the host scanner, in order. */
    val DEFAULT_PROXY_PORTS: List<Int> = listOf(80, 8080)

    /** Per-host timeout in the host scanner. */
    const val HOST_SCAN_TIMEOUT_MS = 8_000

    /** Most hosts scanned in one manual run (a manual tap, never periodic). */
    const val MAX_HOSTS_PER_SCAN = 60

    /** Most scan results kept in storage (newest first). */
    const val MAX_SCAN_RESULTS = 50
}
