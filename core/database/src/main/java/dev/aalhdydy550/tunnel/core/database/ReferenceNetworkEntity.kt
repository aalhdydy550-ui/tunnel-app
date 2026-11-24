package dev.aalhdydy550.tunnel.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Hamo reference free network — reference data ONLY, never a profile.
 *
 * The rows come from the sanitized Hamo v3.3 free-networks list (name,
 * SNI host, tunnel type and carrier info — no credentials of any
 * kind). Same rule as [ReferenceServerEntity]: its own table, never
 * shown as a working server, never connected to. Future use only:
 * the payload generator (Phase 2) reads these rows from the database.
 */
@Entity(tableName = "referenceNetworks")
data class ReferenceNetworkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** SNI host, "" when the source list has none. */
    val sniHost: String,
    /** Tunnel type number as in the source list. */
    val tunnelType: Int,
    val carrierInfo: String,
)
