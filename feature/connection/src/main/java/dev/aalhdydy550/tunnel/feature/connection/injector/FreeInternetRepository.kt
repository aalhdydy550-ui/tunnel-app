package dev.aalhdydy550.tunnel.feature.connection.injector

import dev.aalhdydy550.tunnel.core.database.ReferenceNetworkDao
import java.util.Locale

/**
 * One reference network eligible for injection mode.
 *
 * Only rows with `tunnelType == 3` and a non-blank SNI host qualify
 * (127 of the 195 seeded rows). The rest — other tunnel types, or rows
 * with no SNI host — can never carry an SNI injection and are excluded.
 */
data class NetworkCandidate(
    val id: Long,
    val name: String,
    val sniHost: String,
    val carrier: Carrier,
    val carrierInfo: String,
)

/**
 * Read-only access to the injection candidates.
 *
 * Takes a [ReferenceNetworkDao] in the constructor (the ViewModel passes
 * `TunnelDatabaseHolder.get(context).referenceNetworkDao()`). Never
 * writes to the database: the reference table is seeded once and is
 * never edited from the app.
 */
class FreeInternetRepository(
    private val dao: ReferenceNetworkDao,
) {

    /**
     * The injection candidates, display-ordered: networks matching
     * [carrier] first, then networks the user already verified on their
     * own SIM ([verifiedIds]) first, then by name. Pass
     * [Carrier.UNKNOWN] / an empty set when no carrier is known or
     * nothing is verified yet — the name order still applies.
     */
    suspend fun loadCandidates(
        carrier: Carrier = Carrier.UNKNOWN,
        verifiedIds: Set<Long> = emptySet(),
    ): List<NetworkCandidate> {
        val candidates = dao.getAllOnce()
            .filter { it.tunnelType == INJECTION_TUNNEL_TYPE && it.sniHost.isNotBlank() }
            .map {
                NetworkCandidate(
                    id = it.id,
                    name = it.name,
                    sniHost = it.sniHost.trim(),
                    carrier = CarrierClassifier.fromNetwork(it.name, it.carrierInfo),
                    carrierInfo = it.carrierInfo,
                )
            }
        return sortCandidates(candidates, carrier, verifiedIds)
    }

    /** Pure display ordering, shared by [loadCandidates] and the UI. */
    fun sortCandidates(
        candidates: List<NetworkCandidate>,
        carrier: Carrier,
        verifiedIds: Set<Long>,
    ): List<NetworkCandidate> = candidates.sortedWith(
        compareByDescending<NetworkCandidate> {
            carrier != Carrier.UNKNOWN && it.carrier == carrier
        }
            .thenByDescending { it.id in verifiedIds }
            .thenBy { it.name.lowercase(Locale.US) },
    )

    companion object {
        /** Tunnel type of the injectable reference networks (Hamo type 3). */
        const val INJECTION_TUNNEL_TYPE = 3
    }
}
