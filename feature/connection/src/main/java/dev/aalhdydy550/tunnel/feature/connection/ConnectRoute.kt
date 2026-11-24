package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.repository.ProxyMode

/**
 * Where a connect request goes.
 *
 * - [VPN]: the normal path — `VpnService.prepare()` first, then
 *   [TunnelVpnService] with a config carrying the `tun` inbound.
 * - [PROXY]: proxy-only mode — no VPN permission is asked for at
 *   all; the same sing-box engine runs under [ProxyOnlyService]
 *   with a config that carries only the `mixed` inbound
 *   (`proxyOnly = true` in the config builder).
 * - [OPENVPN]: the separate OpenVPN for Android app owns the VPN
 *   permission — see `OpenVpnLauncher`.
 */
enum class ConnectRoute {
    VPN,
    PROXY,
    OPENVPN,
}

/**
 * Pure routing decision for [ConnectionViewModel.connect]: an
 * OpenVPN profile always goes to the external app, whatever the
 * stored mode (that app owns the VPN permission); otherwise the
 * stored [ProxyMode] decides — [ProxyMode.PROXY] runs the local
 * proxy service, [ProxyMode.VPN] the normal VPN service.
 *
 * Pure Kotlin (no Android dependency) so it can be unit-tested on
 * the JVM — see `ConnectRouteTest`.
 */
internal fun resolveConnectRoute(
    profileType: ProfileType,
    proxyMode: ProxyMode,
): ConnectRoute = when {
    profileType == ProfileType.OPENVPN -> ConnectRoute.OPENVPN
    proxyMode == ProxyMode.PROXY -> ConnectRoute.PROXY
    else -> ConnectRoute.VPN
}
