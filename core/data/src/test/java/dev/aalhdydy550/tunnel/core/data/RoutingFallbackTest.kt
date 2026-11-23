package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataState
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataStatus
import dev.aalhdydy550.tunnel.core.data.routing.RoutingFallback
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the mandatory routing-data fallback:
 * missing data must never break the app — it falls back to the safe
 * default (all traffic through the tunnel) with a UI notice.
 */
class RoutingFallbackTest {

    private fun status(state: RoutingDataState) =
        RoutingDataStatus(state, downloadedAtMs = null)

    @Test
    fun `ready data honors the ads request with no notice`() {
        val decision = RoutingFallback.decide(status(RoutingDataState.READY), true)
        assertEquals(true, decision.useAdsRule)
        assertEquals(false, decision.showStaleNotice)
        assertEquals(false, decision.fallbackActive)
    }

    @Test
    fun `missing data omits the rule, shows the notice, keeps the fallback`() {
        val decision = RoutingFallback.decide(status(RoutingDataState.NOT_DOWNLOADED), true)
        assertEquals(false, decision.useAdsRule)
        assertEquals(true, decision.showStaleNotice)
        assertEquals(true, decision.fallbackActive)
    }

    @Test
    fun `failed download behaves exactly like missing data`() {
        val decision = RoutingFallback.decide(status(RoutingDataState.FAILED), true)
        assertEquals(false, decision.useAdsRule)
        assertEquals(true, decision.showStaleNotice)
        assertEquals(true, decision.fallbackActive)
    }

    @Test
    fun `ads off needs no data and no notice`() {
        val decision = RoutingFallback.decide(status(RoutingDataState.NOT_DOWNLOADED), false)
        assertEquals(false, decision.useAdsRule)
        assertEquals(false, decision.showStaleNotice)
        assertEquals(true, decision.fallbackActive)
    }
}
