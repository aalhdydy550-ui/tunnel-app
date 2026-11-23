package dev.aalhdydy550.tunnel.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stored profile. Mirrors `core.data`'s [Profile][dev.aalhdydy550.tunnel.core.data.model.Profile]
 * one-to-one; the type is stored as the enum name.
 */
@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
    val outboundJson: String,
    val createdAt: Long,
    /** ISO 3166-1 alpha-2 country code, "" when unknown. Added in v2. */
    val countryCode: String = "",
)
