package dev.aalhdydy550.tunnel.feature.connection.injector

import android.content.Context

/**
 * Which reference networks the user has personally verified on their
 * own SIM.
 *
 * A network is stored here ONLY after a successful connection from the
 * user's own phone followed by a passing traffic probe (see
 * `FreeInternetViewModel`): the seeded list is old reference data, and
 * nothing in it may ever be shown as "working" before that.
 */
interface VerifiedNetworkStore {

    fun isVerified(networkId: Long): Boolean

    fun markVerified(networkId: Long, carrier: Carrier)

    /** Epoch millis when the network was verified, or null if never. */
    fun verifiedAt(networkId: Long): Long?

    fun allVerified(): Set<Long>
}

/**
 * [VerifiedNetworkStore] backed by its own SharedPreferences file
 * ("free_internet_verified"). No DataStore, no new dependency.
 *
 * Keys: `verified_at_<id>` → epoch millis, `verified_carrier_<id>` →
 * carrier name. Only the timestamp decides verification; the carrier
 * is kept for display/diagnostics.
 */
class SharedPreferencesVerifiedNetworkStore(
    context: Context,
) : VerifiedNetworkStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    override fun isVerified(networkId: Long): Boolean =
        verifiedAt(networkId) != null

    override fun markVerified(networkId: Long, carrier: Carrier) {
        prefs.edit()
            .putLong(timeKey(networkId), System.currentTimeMillis())
            .putString(carrierKey(networkId), carrier.name)
            .apply()
    }

    override fun verifiedAt(networkId: Long): Long? {
        val value = prefs.getLong(timeKey(networkId), -1L)
        return if (value < 0) null else value
    }

    override fun allVerified(): Set<Long> = prefs.all.keys
        .filter { it.startsWith(TIME_KEY_PREFIX) }
        .mapNotNull { it.removePrefix(TIME_KEY_PREFIX).toLongOrNull() }
        .toSet()

    private fun timeKey(networkId: Long) = "$TIME_KEY_PREFIX$networkId"

    private fun carrierKey(networkId: Long) = "$CARRIER_KEY_PREFIX$networkId"

    companion object {
        const val PREFS_NAME = "free_internet_verified"
        private const val TIME_KEY_PREFIX = "verified_at_"
        private const val CARRIER_KEY_PREFIX = "verified_carrier_"
    }
}
