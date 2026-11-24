package dev.aalhdydy550.tunnel.core.data.singbox

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.net.DEFAULT_LOCAL_PROXY_PORT
import dev.aalhdydy550.tunnel.core.data.net.RESERVED_INJECTOR_SOCKS_PORT
import dev.aalhdydy550.tunnel.core.data.net.localProxyListenAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds a complete sing-box configuration document from a [Profile].
 *
 * The shape follows the sing-box 1.14 schema used by sing-box-for-android:
 * one TUN inbound (gVisor stack, auto-route), DNS via TLS to 1.1.1.1 with a
 * local fallback, and a global routing table — everything goes through the
 * selected profile (`final: proxy`), private IPs go direct, DNS queries go
 * through the `dns-out` outbound.
 *
 * For [ProfileType.SINGBOX_JSON] profiles the stored document is returned
 * as-is (the user supplied a full configuration).
 *
 * For [ProfileType.ZIVPN] profiles the stored document is the ZIVPN
 * `client.json`; the builder emits the standard configuration with a
 * `socks` outbound pointing at the local SOCKS5 endpoint the external
 * `udp-zivpn` process exposes (Phase 2 — the process itself is started by
 * the service layer, see `docs/PHASE2.md`). The SOCKS port is read from
 * the client document (`socks_port`, `socks5_port` or `local_port`) and
 * defaults to 1080.
 *
 * A second inbound is always emitted (except for [ProfileType.SINGBOX_JSON]
 * profiles, returned as-is): a `mixed` inbound (`mixed-in`) on the local
 * proxy port (default 10808, like Hamo) so local apps can proxy through
 * the tunnel without the VPN interface.
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 * The produced document is validated at runtime with `Libbox.checkConfig`
 * before the service starts.
 */
object SingBoxConfigBuilder {

    private val json = Json { ignoreUnknownKeys = true }

    /** Default remote DNS (Cloudflare DoT) — the fallback when the DNS
     * selection finds no faster resolver. */
    const val DEFAULT_DNS_SERVER = "tls://1.1.1.1"

    fun buildConfig(
        profile: Profile,
        autoRoute: Boolean = true,
        strictRoute: Boolean = true,
        /**
         * Ad blocking via the downloaded `geosite-category-ads-all.srs`
         * rule-set. The rule is emitted ONLY when the file actually
         * exists in [ruleSetsDir] — when it does not (never downloaded,
         * or the download failed), the rule is omitted and the config
         * stays valid: all traffic goes through the tunnel. That is the
         * safe fallback (see `routing/RoutingData.kt`); the UI shows the
         * stale-data notice and offers a retry.
         */
        adsBlocking: Boolean = false,
        ruleSetsDir: String? = null,
        /**
         * Remote DNS server address. Normally the winner of
         * [dev.aalhdydy550.tunnel.core.data.net.DnsSelector]
         * (fastest public resolver at connect time); the default is the
         * Cloudflare fallback.
         */
        dnsServer: String = DEFAULT_DNS_SERVER,
        /**
         * Per-app proxy (split tunneling): packages for the TUN
         * inbound's `include_package` / `exclude_package` fields,
         * which `TunnelVpnService` applies with
         * `addAllowed/DisallowedApplication`. Both empty (the default)
         * means every app goes through the tunnel — the behaviour
         * before the per-app screen existed. At most one list is
         * emitted: when both are passed, `include_package` wins and
         * `exclude_package` is dropped (sing-box rejects a config
         * carrying both). The only caller guarantees exclusivity
         * (see `PerAppProxySettings`), so this is a defensive rule
         * only.
         */
        includePackages: List<String> = emptyList(),
        excludePackages: List<String> = emptyList(),
        /**
         * Local mixed (HTTP/SOCKS) proxy port, Hamo-style. The default
         * ([DEFAULT_LOCAL_PROXY_PORT], 10808) matches
         * Hamo's local proxy. Defensive fallback only: the settings
         * storage layer rejects invalid values before they reach here,
         * but a port outside 1024..65535 — or the injector's reserved
         * SOCKS port ([RESERVED_INJECTOR_SOCKS_PORT],
         * 10810) — is silently replaced with the default (see
         * [effectiveLocalProxyPort]) instead of producing an invalid
         * config.
         */
        localProxyPort: Int = DEFAULT_LOCAL_PROXY_PORT,
        /**
         * Whether the local mixed proxy listens on all interfaces
         * (`0.0.0.0`, reachable from other devices on the LAN) instead
         * of loopback only (`127.0.0.1`). Default false: local only.
         */
        allowOtherDevices: Boolean = false,
        /**
         * Skip TLS certificate verification for the profile's main
         * outbound (`tls.insecure: true`). Applies ONLY when the built
         * outbound already carries a `tls` object (VLESS/VMess/Trojan
         * and similar); outbounds without TLS (WireGuard, plain SOCKS
         * such as ZIVPN) are left untouched and no `tls` key is ever
         * added. Default false: the outbound is emitted byte-identical
         * to before this parameter existed.
         */
        allowInsecure: Boolean = false,
    ): String {
        if (profile.type == ProfileType.SINGBOX_JSON) {
            return profile.outboundJson
        }
        val outbound: MutableMap<String, kotlinx.serialization.json.JsonElement> =
            if (profile.type == ProfileType.ZIVPN) {
                // Phase 2: the external udp-zivpn process exposes a local SOCKS5.
                val socksPort = zivpnSocksPort(profile.outboundJson)
                mutableMapOf(
                    "type" to JsonPrimitive("socks"),
                    "server" to JsonPrimitive("127.0.0.1"),
                    "server_port" to JsonPrimitive(socksPort),
                    "version" to JsonPrimitive("5"),
                )
            } else {
                json.parseToJsonElement(profile.outboundJson).jsonObject.toMutableMap()
            }
        outbound["tag"] = JsonPrimitive("proxy")

        // Hamo setting: skip TLS verification for the main outbound.
        // Only when the outbound already carries a "tls" object; when
        // allowInsecure is false (the default) the outbound is not
        // touched at all, and outbounds without TLS (WireGuard, plain
        // SOCKS such as ZIVPN) never gain a "tls" key.
        if (allowInsecure) {
            val tls = outbound["tls"]
            if (tls is JsonObject) {
                outbound["tls"] = buildJsonObject {
                    tls.forEach { (key, value) -> put(key, value) }
                    put("insecure", true)
                }
            }
        }

        // Ad blocking (only when the downloaded rule-set file exists —
        // the safe fallback omits the rule otherwise).
        val adsRuleSetPath = if (adsBlocking && ruleSetsDir != null) {
            val file = java.io.File(
                ruleSetsDir,
                dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog.ADS_RULE_SET_FILE,
            )
            if (file.exists() && file.length() > 0) file.absolutePath else null
        } else {
            null
        }

        val config = buildJsonObject {
            putJsonObject("log") {
                put("level", "info")
                put("timestamp", true)
            }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(
                        buildJsonObject {
                            put("tag", "remote")
                            put("address", dnsServer)
                        },
                    )
                    add(
                        buildJsonObject {
                            put("tag", "local")
                            put("address", "local")
                            put("detour", "direct")
                        },
                    )
                }
                put("final", "remote")
                put("strategy", "ipv4_only")
            }
            putJsonArray("inbounds") {
                add(
                    buildJsonObject {
                        put("type", "tun")
                        put("tag", "tun-in")
                        put("interface_name", "tun0")
                        put("inet4_address", "172.19.0.1/30")
                        put("inet6_address", "fdfe:dcba:9876::1/126")
                        put("mtu", 9000)
                        put("auto_route", autoRoute)
                        put("strict_route", strictRoute)
                        put("stack", "gvisor")
                        put("sniff", true)
                        put("domain_strategy", "ipv4_only")
                        // Per-app proxy: at most one of the two lists
                        // (see the `includePackages` parameter doc).
                        val include = includePackages.filter { it.isNotBlank() }
                        val exclude = excludePackages.filter { it.isNotBlank() }
                        if (include.isNotEmpty()) {
                            putJsonArray("include_package") {
                                include.forEach { add(it) }
                            }
                        } else if (exclude.isNotEmpty()) {
                            putJsonArray("exclude_package") {
                                exclude.forEach { add(it) }
                            }
                        }
                    },
                )
                // Hamo setting: local mixed (HTTP/SOCKS) proxy so local
                // apps can use the tunnel without the VPN interface.
                // Second inbound, after TUN; the listen address follows
                // allowOtherDevices and the port is the defensive
                // effective port (see effectiveLocalProxyPort).
                add(
                    buildJsonObject {
                        put("type", "mixed")
                        put("tag", "mixed-in")
                        put(
                            "listen",
                            localProxyListenAddress(allowOtherDevices),
                        )
                        put("listen_port", effectiveLocalProxyPort(localProxyPort))
                    },
                )
            }
            putJsonArray("outbounds") {
                add(JsonObject(outbound))
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                add(buildJsonObject { put("type", "block"); put("tag", "block") })
                add(buildJsonObject { put("type", "dns"); put("tag", "dns-out") })
            }
            putJsonObject("route") {
                if (adsRuleSetPath != null) {
                    putJsonArray("rule_set") {
                        add(
                            buildJsonObject {
                                put("type", "local")
                                put(
                                    "tag",
                                    dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog.ADS_RULE_SET_TAG,
                                )
                                put("format", "binary")
                                put("path", adsRuleSetPath)
                            },
                        )
                    }
                }
                putJsonArray("rules") {
                    if (adsRuleSetPath != null) {
                        add(
                            buildJsonObject {
                                putJsonArray("rule_set") {
                                    add(
                                        JsonPrimitive(
                                            dev.aalhdydy550.tunnel.core.data.routing.RoutingDataCatalog.ADS_RULE_SET_TAG,
                                        ),
                                    )
                                }
                                put("outbound", "block")
                            },
                        )
                    }
                    add(
                        buildJsonObject {
                            put("protocol", "dns")
                            put("outbound", "dns-out")
                        },
                    )
                    add(
                        buildJsonObject {
                            put("ip_is_private", true)
                            put("outbound", "direct")
                        },
                    )
                }
                put("final", "proxy")
                put("auto_detect_interface", true)
            }
        }
        return config.toString()
    }

    /**
     * Defensive effective local-proxy port. The settings storage layer
     * rejects invalid values before they reach the builder, so this is
     * a second line of defence only: a port outside the unprivileged
     * range 1024..65535, or the injector's reserved SOCKS port
     * ([RESERVED_INJECTOR_SOCKS_PORT], 10810 — the
     * injector's own SOCKS5 server must never collide with the mixed
     * inbound), falls back to the default
     * ([DEFAULT_LOCAL_PROXY_PORT], 10808).
     */
    fun effectiveLocalProxyPort(port: Int): Int =
        if (port in 1024..65535 && port != RESERVED_INJECTOR_SOCKS_PORT) {
            port
        } else {
            DEFAULT_LOCAL_PROXY_PORT
        }

    /**
     * Local SOCKS5 port of the external ZIVPN process, read from its
     * `client.json` document. The exact key is confirmed against the real
     * client schema during the Phase 2 implementation; 1080 is the
     * conventional default.
     */
    fun zivpnSocksPort(clientJson: String): Int {
        return try {
            val doc = json.parseToJsonElement(clientJson).jsonObject
            listOf("socks_port", "socks5_port", "local_port")
                .firstNotNullOfOrNull { key ->
                    doc[key]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it in 1..65535 }
                } ?: DEFAULT_ZIVPN_SOCKS_PORT
        } catch (e: Exception) {
            DEFAULT_ZIVPN_SOCKS_PORT
        }
    }

    const val DEFAULT_ZIVPN_SOCKS_PORT = 1080
}
