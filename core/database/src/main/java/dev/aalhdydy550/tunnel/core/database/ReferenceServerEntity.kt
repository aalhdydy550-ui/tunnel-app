package dev.aalhdydy550.tunnel.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A Hamo reference server — reference data ONLY, never a profile.
 *
 * The rows come from the sanitized Hamo v3.3 default server list
 * (names, hosts, ports, protocol numbers and flags — no credentials
 * of any kind). They live in their own table so they can never leak
 * into the connectable server list: no DAO here feeds [ProfileDao],
 * no UI reads this table as servers, and nothing in the app ever
 * connects to a [host] from this table.
 *
 * Future use only: Phase 2 (ZIVPN) and the payload generator read
 * these rows from the database. Ports are stored as text exactly as
 * in the source list — some are ranges such as `6000-9500`, not
 * single ports — so an `Int` column would lose data.
 */
@Entity(tableName = "referenceServers")
data class ReferenceServerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val host: String,
    val tcpPort: String,
    val sslPort: String,
    /** Protocol number as in the source list (0–3). */
    val protocol: Int,
    /** ISO country code, e.g. `fr`. */
    val flag: String,
)
