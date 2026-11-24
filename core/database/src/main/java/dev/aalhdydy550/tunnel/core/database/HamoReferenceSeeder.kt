package dev.aalhdydy550.tunnel.core.database

import androidx.room.withTransaction

/**
 * Seeds the Hamo reference tables once, from [HamoReferenceSeed].
 *
 * Runs on app start (see `TunnelApp`): each table is filled only
 * when it is empty, inside one transaction, so a crash mid-seed rolls
 * back and the next start retries cleanly. Re-running after a
 * successful seed is a no-op (two cheap `COUNT(*)` queries on 23 +
 * 195 rows) — no battery or memory cost beyond that.
 *
 * This is the ONLY writer of the reference tables, and it writes
 * reference data only: it never touches `profiles` or `subscriptions`,
 * and it never connects to any host.
 *
 * Plain class (no Hilt annotations): `:core:database` has no Hilt
 * dependency. The instance is provided by `DataModule` in `:core:data`.
 */
class HamoReferenceSeeder(
    private val database: TunnelDatabase,
) {
    /** Inserts the 23 servers + 195 networks when the tables are empty. */
    suspend fun ensureSeeded() {
        database.withTransaction {
            if (database.referenceServerDao().count() == 0) {
                database.referenceServerDao().insertAll(HamoReferenceSeed.SERVERS)
            }
            if (database.referenceNetworkDao().count() == 0) {
                database.referenceNetworkDao().insertAll(HamoReferenceSeed.NETWORKS)
            }
        }
    }
}
