package dev.aalhdydy550.tunnel.core.data.routing

/**
 * Per-app proxy (split tunneling) settings.
 *
 * The engine side already exists and is untouched: `TunnelVpnService`
 * applies `addAllowedApplication` / `addDisallowedApplication` from the
 * sing-box TUN options, and [dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder]
 * emits the matching `include_package` / `exclude_package` fields. This
 * model is only the user's choice — which mode, and which packages.
 *
 * The choice applies at the next connect: the config is built once per
 * connection, so changing it while the tunnel runs needs a reconnect.
 * The stored package set is kept across mode switches, so flipping
 * between "selected only" and "except selected" never loses the list.
 */
enum class PerAppProxyMode(
    val arabicName: String,
    val arabicDescription: String,
) {
    ALL(
        "كل التطبيقات",
        "كل التطبيقات تمر عبر النفق — كما هو الآن.",
    ),
    INCLUDE_ONLY(
        "تطبيقات مختارة فقط",
        "فقط التطبيقات التي تختارها تمر عبر النفق، والباقي يتصل مباشرة.",
    ),
    EXCLUDE_SELECTED(
        "استثناء تطبيقات مختارة",
        "كل التطبيقات تمر عبر النفق ما عدا التي تستثنيها — مثل تطبيقات " +
            "البنك والتطبيقات المحلية.",
    ),
}

data class PerAppProxySettings(
    val mode: PerAppProxyMode = PerAppProxyMode.ALL,
    val packages: Set<String> = emptySet(),
) {
    /**
     * Packages for the sing-box `include_package` field: non-empty
     * only in [PerAppProxyMode.INCLUDE_ONLY]. Sorted, so the built
     * config is deterministic.
     */
    fun includePackages(): List<String> =
        if (mode == PerAppProxyMode.INCLUDE_ONLY) {
            packages.filter { it.isNotBlank() }.sorted()
        } else {
            emptyList()
        }

    /**
     * Packages for the sing-box `exclude_package` field: non-empty
     * only in [PerAppProxyMode.EXCLUDE_SELECTED].
     */
    fun excludePackages(): List<String> =
        if (mode == PerAppProxyMode.EXCLUDE_SELECTED) {
            packages.filter { it.isNotBlank() }.sorted()
        } else {
            emptyList()
        }
}
