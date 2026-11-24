package dev.aalhdydy550.tunnel.feature.connection

import java.net.Socket

/**
 * TCP No Delay (§4-#18): the single place that turns the setting
 * into a socket option.
 *
 * Why a socket call and not a sing-box config key: sing-box 1.14.4
 * (the version this app ships) has NO `tcp_nodelay` dialer field —
 * its `DialerOptions` offer `tcp_fast_open`, `tcp_multi_path` and
 * keep-alive knobs, but no Nagle switch — and sing-box rejects
 * unknown config fields at startup, so inventing one would break
 * the tunnel. sing-box's own outbound sockets therefore cannot be
 * switched from configuration; the option belongs on the sockets
 * the app itself opens.
 *
 * Today the app opens exactly one such socket: the injector's TLS +
 * SNI socket ([dev.aalhdydy550.tunnel.feature.connection.injector
 * .TlsSniSocketFactory]), which already sets `tcpNoDelay = true`
 * unconditionally. The integration step (see the delivery summary)
 * threads this setting through that factory so the option becomes
 * opt-in as specified — off by default — instead of always on.
 *
 * [apply] is deliberately explicit: `enabled = false` writes
 * `false` to the socket, so the default path provably leaves the
 * socket exactly as the OS created it.
 */
object TcpNoDelay {

    /** Off by default: the default path changes no socket behaviour. */
    const val DEFAULT_ENABLED = false

    fun apply(socket: Socket, enabled: Boolean) {
        socket.tcpNoDelay = enabled
    }
}
