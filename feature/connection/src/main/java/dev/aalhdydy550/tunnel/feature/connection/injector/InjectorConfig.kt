package dev.aalhdydy550.tunnel.feature.connection.injector

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Builds the complete sing-box configuration document for injector
 * mode, mirroring the standard document produced by
 * `SingBoxConfigBuilder` field for field — same TUN inbound, same
 * DNS, same routing table — with exactly one difference: the main
 * outbound is a `socks` outbound pointing at the local SOCKS5
 * endpoint the JSch dynamic forward exposes
 * (127.0.0.1:[InjectorConstants.INJECTOR_SOCKS_PORT]) instead of
 * the profile's outbound. There is no `ssh` outbound and no TLS
 * block anywhere in this document: TLS lives in the JSch socket
 * factory ([TlsSniSocketFactory]), not in sing-box.
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 * The produced document is validated at runtime with
 * `Libbox.checkConfig` before the service starts (same as the
 * standard path).
 */
object InjectorConfig {

    fun buildInjectorConfig(): String {
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
                            put("address", "tls://1.1.1.1")
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
                        put("auto_route", true)
                        put("strict_route", true)
                        put("stack", "gvisor")
                        put("sniff", true)
                        put("domain_strategy", "ipv4_only")
                    },
                )
            }
            putJsonArray("outbounds") {
                add(
                    buildJsonObject {
                        put("type", "socks")
                        put("server", InjectorConstants.SOCKS_BIND_ADDRESS)
                        put("server_port", InjectorConstants.INJECTOR_SOCKS_PORT)
                        put("version", "5")
                        put("tag", "proxy")
                    },
                )
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                add(buildJsonObject { put("type", "block"); put("tag", "block") })
                add(buildJsonObject { put("type", "dns"); put("tag", "dns-out") })
            }
            putJsonObject("route") {
                putJsonArray("rules") {
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
}
