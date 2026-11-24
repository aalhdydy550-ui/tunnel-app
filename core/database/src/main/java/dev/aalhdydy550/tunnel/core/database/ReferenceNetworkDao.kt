package dev.aalhdydy550.tunnel.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the reference-networks table. There is deliberately
 * no update/delete: the reference data is seeded once and never edited
 * from the app.
 */
@Dao
interface ReferenceNetworkDao {

    @Query("SELECT * FROM referenceNetworks ORDER BY id ASC")
    fun getAll(): Flow<List<ReferenceNetworkEntity>>

    @Query("SELECT * FROM referenceNetworks ORDER BY id ASC")
    suspend fun getAllOnce(): List<ReferenceNetworkEntity>

    @Insert
    suspend fun insertAll(networks: List<ReferenceNetworkEntity>)

    @Query("SELECT COUNT(*) FROM referenceNetworks")
    suspend fun count(): Int
}
