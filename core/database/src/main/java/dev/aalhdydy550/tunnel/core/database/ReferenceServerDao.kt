package dev.aalhdydy550.tunnel.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Read access to the reference-servers table. There is deliberately no
 * update/delete: the reference data is seeded once and never edited
 * from the app.
 */
@Dao
interface ReferenceServerDao {

    @Query("SELECT * FROM referenceServers ORDER BY id ASC")
    fun getAll(): Flow<List<ReferenceServerEntity>>

    @Query("SELECT * FROM referenceServers ORDER BY id ASC")
    suspend fun getAllOnce(): List<ReferenceServerEntity>

    @Insert
    suspend fun insertAll(servers: List<ReferenceServerEntity>)

    @Query("SELECT COUNT(*) FROM referenceServers")
    suspend fun count(): Int
}
