package dev.aalhdydy550.tunnel.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY createdAt ASC")
    fun getAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getById(id: Long): ProfileEntity?

    @Insert
    suspend fun insert(profile: ProfileEntity): Long

    @Update
    suspend fun update(profile: ProfileEntity)

    @Delete
    suspend fun delete(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Profiles that came from one subscription (null = manual). */
    @Query("SELECT * FROM profiles WHERE subscriptionId = :subscriptionId ORDER BY createdAt ASC")
    suspend fun getBySubscriptionId(subscriptionId: Long): List<ProfileEntity>

    /** Deletes only the profiles of one subscription. */
    @Query("DELETE FROM profiles WHERE subscriptionId = :subscriptionId")
    suspend fun deleteBySubscriptionId(subscriptionId: Long)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int
}
