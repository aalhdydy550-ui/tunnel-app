package dev.aalhdydy550.tunnel.core.data.model

/**
 * A saved VPN server profile.
 *
 * [outboundJson] is a sing-box outbound JSON fragment (for example the `vless`
 * object). For [ProfileType.SINGBOX_JSON] it holds a complete sing-box
 * configuration document instead, stored as-is. For [ProfileType.ZIVPN] it
 * holds the ZIVPN `client.json` document (Phase 2 — see `docs/PHASE2.md`).
 * For [ProfileType.OPENVPN] it holds a JSON document with a `config` field
 * carrying the normalised `.ovpn` text (see `OvpnConfParser`).
 *
 * This model is pure Kotlin (no Android dependency) so the parsers and the
 * config builder can be unit-tested on the JVM.
 */
enum class ProfileType {
    VLESS,
    VMESS,
    TROJAN,
    SHADOWSOCKS,
    WIREGUARD,
    HYSTERIA2,
    TUIC,
    /**
     * SSH tunnel (sing-box `ssh` outbound, native in v0.1): user/password
     * auth; key auth is via a raw sing-box JSON profile (Phase 2 may add
     * a key picker). Same engine as the original's Trilead SSH, minus
     * the separate service and the external processes.
     */
    SSH,
    SINGBOX_JSON,
    /**
     * Phase 2 (not connectable in v0.1). ZIVPN runs as an external process
     * exposing a local SOCKS5; see [Backend.EXTERNAL_PROCESS].
     */
    ZIVPN,
    /**
     * OpenVPN profile (`.ovpn` file): connected through the separate
     * **OpenVPN for Android** app via its Remote API — see
     * `feature:connection`'s `OpenVpnLauncher`. No sing-box outbound
     * is ever built for it, and [Profile.backend] stays
     * [Backend.SING_BOX] (the `else` branch) because the tunnel is
     * not served by an external process of ours.
     */
    OPENVPN,
}

/**
 * How a profile's traffic is produced.
 *
 * [SING_BOX] — the outbound is handled natively by the sing-box core
 * (libbox). [EXTERNAL_PROCESS] — a native helper runs as an external
 * process and exposes a local SOCKS5 endpoint; sing-box then routes
 * through it with a `socks` outbound. This is the extension point for
 * Phase 2 protocols (ZIVPN, DNSTT) — adding one needs a new [ProfileType],
 * a process launcher, and a SOCKS port, but no restructuring of the
 * profile model, the config builder, or the service layer.
 */
enum class Backend {
    SING_BOX,
    EXTERNAL_PROCESS,
}

data class Profile(
    val id: Long = 0,
    val name: String,
    val type: ProfileType,
    /** Display host (empty for raw sing-box JSON profiles). */
    val server: String,
    /** Display port (0 for raw sing-box JSON profiles). */
    val port: Int,
    val outboundJson: String,
    val createdAt: Long = System.currentTimeMillis(),
    /**
     * Optional ISO 3166-1 alpha-2 country code (for example "DE"), shown
     * as a flag emoji next to the server. Empty when unknown — the app
     * never guesses a country from a hostname or an IP. Set by the user
     * at import, or filled from the offline GeoIP data in Phase 2.
     */
    val countryCode: String = "",
    /**
     * The subscription this profile came from, or null for a manually
     * imported profile. A subscription refresh replaces only the
     * profiles with its own id.
     */
    val subscriptionId: Long? = null,
) {
    val backend: Backend
        get() = when (type) {
            ProfileType.ZIVPN -> Backend.EXTERNAL_PROCESS
            else -> Backend.SING_BOX
        }

    /** Flag emoji for [countryCode], or "" when it is not a valid code. */
    val flagEmoji: String
        get() = flagEmojiFor(countryCode)
}

/**
 * Regional-indicator flag emoji for an ISO 3166-1 alpha-2 code.
 * Returns "" for anything that is not exactly two ASCII letters — the
 * lightest possible flag: no image assets (the original shipped 206
 * flag pictures in `assets/flags/`), no network lookup.
 */
fun flagEmojiFor(countryCode: String): String {
    val code = countryCode.trim().uppercase(java.util.Locale.US)
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return ""
    return code.map { letter ->
        String(Character.toChars(0x1F1E6 + (letter - 'A')))
    }.joinToString("")
}

/**
 * Server identity for duplicate detection on import: same protocol +
 * same server + same port is the same server. Importing the same
 * `.conf` or link twice must update the stored row, never add a
 * second identical row.
 *
 * Raw sing-box JSON profiles carry no server/port ("" / 0), so the
 * document itself is part of their identity — otherwise every JSON
 * profile would "duplicate" every other one.
 */
fun isSameServer(a: Profile, b: Profile): Boolean {
    if (a.type != b.type || a.server != b.server || a.port != b.port) return false
    return if (a.type == ProfileType.SINGBOX_JSON) {
        a.outboundJson == b.outboundJson
    } else {
        true
    }
}
