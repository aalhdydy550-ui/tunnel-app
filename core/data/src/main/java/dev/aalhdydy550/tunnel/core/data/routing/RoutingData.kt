package dev.aalhdydy550.tunnel.core.data.routing

/**
 * Routing data: replacement, not deletion.
 *
 * The original app embedded `geoip.dat` (15.3 MB) and `geosite.dat`
 * (2.2 MB) — old, frozen at build time, and 17.5 MB of the install.
 * This app embeds NO routing data files at all. Instead it downloads
 * small, current sing-box rule-sets (`.srs`, binary format) from the
 * official sing-geoip / sing-geosite repositories — the ads set below
 * is 8,175 bytes — and keeps them in the app-private `rule-sets/`
 * directory, where they can be updated without reinstalling the app.
 *
 * The mandatory fallback (user rule, 2026-11-23): if the data is not
 * present at runtime (no internet on first run, download failed), the
 * app does NOT break or fail. It falls back to the safe default — all
 * traffic through the tunnel — shows a notice that the routing data
 * is not updated, and retries when the network is available.
 * [RoutingFallback.decide] is that rule, and it is unit-tested.
 */

/** Where the routing data stands on this device. */
enum class RoutingDataState {
    /** Never downloaded (or the file was deleted). */
    NOT_DOWNLOADED,

    /** The file is present and usable. */
    READY,

    /** The last download attempt failed and no file exists. */
    FAILED,
}

data class RoutingDataStatus(
    val state: RoutingDataState,
    /** Last-modified time of the downloaded file, or null. */
    val downloadedAtMs: Long?,
)

/**
 * The rule-set catalog.
 *
 * v0.1 downloads one file: the ads category (the `-all` variant —
 * the widest ad list sing-geosite publishes; it is generated from
 * domain-list-community + Loyalsoldier's anti-ad rules), which powers
 * the ad-blocking route rule. Country bypass and domain routing (the
 * other rules the old `.dat` files fed) are Phase 2 modes — they will
 * use the same pattern (`geoip-{cc}.srs` from sing-geoip,
 * `geosite-{category}.srs` from sing-geosite) and the same fallback.
 */
object RoutingDataCatalog {
    const val ADS_RULE_SET_TAG = "geosite-category-ads-all"
    const val ADS_RULE_SET_FILE = "geosite-category-ads-all.srs"

    /** Verified 2026-11-23: HTTP 200, 8,175 bytes. */
    const val ADS_RULE_SET_URL =
        "https://raw.githubusercontent.com/SagerNet/sing-geosite/" +
            "rule-set/geosite-category-ads-all.srs"

    /** Pattern for the Phase 2 country sets (not downloaded in v0.1). */
    fun geoipUrl(countryCode: String): String =
        "https://raw.githubusercontent.com/SagerNet/sing-geoip/" +
            "rule-set/geoip-${countryCode.lowercase()}.srs"
}

/** The fallback decision. See the file doc above. */
object RoutingFallback {

    data class Decision(
        /** Emit the ads-blocking rule into the sing-box config. */
        val useAdsRule: Boolean,
        /** Show "routing data is not updated" in the UI. */
        val showStaleNotice: Boolean,
        /** The safe default is active (all traffic through the tunnel). */
        val fallbackActive: Boolean,
    )

    /**
     * [adsBlockingRequested] is the user's toggle. When the data is
     * ready the request is honored; otherwise the rule is silently
     * omitted, the fallback stays active, and the UI must show the
     * notice + offer a retry. When ads blocking is off, nothing
     * depends on the data, so no notice is needed.
     */
    fun decide(
        status: RoutingDataStatus,
        adsBlockingRequested: Boolean,
    ): Decision {
        if (!adsBlockingRequested) {
            return Decision(
                useAdsRule = false,
                showStaleNotice = false,
                fallbackActive = true,
            )
        }
        return when (status.state) {
            RoutingDataState.READY -> Decision(
                useAdsRule = true,
                showStaleNotice = false,
                fallbackActive = false,
            )
            RoutingDataState.NOT_DOWNLOADED,
            RoutingDataState.FAILED,
            -> Decision(
                useAdsRule = false,
                showStaleNotice = true,
                fallbackActive = true,
            )
        }
    }
}
