package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.repository.ProxyMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [resolveConnectRoute]: every combination of
 * profile type and stored proxy mode.
 *
 * - OpenVPN profiles always go to the external app, whatever the
 *   mode (that app owns the VPN permission).
 * - Any other profile type follows the stored mode: PROXY runs the
 *   proxy-only service, VPN the normal VPN service.
 */
class ConnectRouteTest {

    /** Profile types that go through our own sing-box engine. */
    private val engineTypes = listOf(
        ProfileType.VLESS,
        ProfileType.VMESS,
        ProfileType.TROJAN,
        ProfileType.SHADOWSOCKS,
        ProfileType.WIREGUARD,
        ProfileType.HYSTERIA2,
        ProfileType.TUIC,
        ProfileType.SSH,
        ProfileType.SINGBOX_JSON,
        ProfileType.ZIVPN,
    )

    @Test
    fun `openvpn always routes to openvpn whatever the mode`() {
        for (mode in ProxyMode.entries) {
            assertEquals(
                "OPENVPN + $mode",
                ConnectRoute.OPENVPN,
                resolveConnectRoute(ProfileType.OPENVPN, mode),
            )
        }
    }

    @Test
    fun `proxy mode routes every engine profile to proxy`() {
        for (type in engineTypes) {
            assertEquals(
                "$type + PROXY",
                ConnectRoute.PROXY,
                resolveConnectRoute(type, ProxyMode.PROXY),
            )
        }
    }

    @Test
    fun `vpn mode routes every engine profile to vpn`() {
        for (type in engineTypes) {
            assertEquals(
                "$type + VPN",
                ConnectRoute.VPN,
                resolveConnectRoute(type, ProxyMode.VPN),
            )
        }
    }
}
