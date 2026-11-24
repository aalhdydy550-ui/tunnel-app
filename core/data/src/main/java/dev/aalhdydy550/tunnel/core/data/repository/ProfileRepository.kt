package dev.aalhdydy550.tunnel.core.data.repository

import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.backup.importProfilesFromJson
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.model.isSameServer
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.profile.WireGuardConfParser
import dev.aalhdydy550.tunnel.core.database.ProfileDao
import dev.aalhdydy550.tunnel.core.database.ProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Profile storage + import. All parsing is delegated to the pure-Kotlin
 * parsers ([ProfileLinkParser], [WireGuardConfParser]) so the import rules
 * are unit-tested without Android.
 */
@Singleton
class ProfileRepository @Inject constructor(
    private val dao: ProfileDao,
) {
    val profiles: Flow<List<Profile>> = dao.getAll().map { list ->
        list.mapNotNull { it.toModel() }
    }

    suspend fun getById(id: Long): Profile? = dao.getById(id)?.toModel()

    suspend fun add(profile: Profile): Long =
        dao.insert(profile.toEntity())

    /**
     * Adds [profile], or updates the stored profile with the same
     * server identity ([isSameServer]) instead of creating a
     * duplicate: importing the same `.conf` / link twice must never
     * produce two identical rows in the server list. The stored row
     * keeps its id, its position ([Profile.createdAt]) and its
     * subscription link; the fresh import's name and config win, and
     * a blank country code keeps the stored one (so a re-import
     * without a country never wipes the flag). Returns the stored
     * row's id.
     */
    suspend fun addOrReplace(profile: Profile): Long {
        val existing = profiles.first().firstOrNull { isSameServer(it, profile) }
        return if (existing == null) {
            dao.insert(profile.toEntity())
        } else {
            val merged = profile.copy(
                id = existing.id,
                createdAt = existing.createdAt,
                subscriptionId = existing.subscriptionId,
                countryCode = profile.countryCode.ifBlank { existing.countryCode },
            )
            dao.update(merged.toEntity())
            existing.id
        }
    }

    suspend fun update(profile: Profile) {
        dao.update(profile.toEntity())
    }

    suspend fun delete(profile: Profile) {
        dao.deleteById(profile.id)
    }

    /** Deletes only the profiles that came from one subscription. */
    suspend fun deleteBySubscriptionId(subscriptionId: Long) {
        dao.deleteBySubscriptionId(subscriptionId)
    }

    /** The whole list as a clean JSON backup document. */
    suspend fun exportToJson(): String = exportProfilesToJson(profiles.first())

    /**
     * Imports profiles from pasted / file text.
     *
     * Dispatch by content: a `[Interface]` block is a WireGuard `.conf`;
     * text starting with `{` is a raw sing-box JSON configuration; text
     * starting with `[` is a JSON backup produced by [exportToJson];
     * anything else is parsed line-by-line as proxy links.
     */
    fun importFromText(text: String, fileName: String = ""): ProfileLinkParser.ImportResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) {
            return ProfileLinkParser.ImportResult(emptyList(), listOf("empty input"))
        }
        // WireGuard `.conf` first: a .conf starts with `[Interface]`, so the
        // JSON-array check below (`startsWith("[")`) would swallow it and
        // report "invalid backup JSON" for every .conf import.
        if ("[Interface]" in trimmed && "[Peer]" in trimmed) {
            return try {
                val profile = WireGuardConfParser.parseConf(trimmed, fileName)
                ProfileLinkParser.ImportResult(listOf(profile), emptyList())
            } catch (e: IllegalArgumentException) {
                ProfileLinkParser.ImportResult(emptyList(), listOf(e.message ?: "invalid WireGuard config"))
            }
        }
        if (trimmed.startsWith("[")) {
            return importProfilesFromJson(trimmed)
        }
        if (trimmed.startsWith("{")) {
            return try {
                val parsed = Json.parseToJsonElement(trimmed).jsonObject
                require("outbounds" in parsed) { "JSON is not a sing-box config (missing 'outbounds')" }
                val profile = Profile(
                    name = fileName.ifBlank { "sing-box config" },
                    type = ProfileType.SINGBOX_JSON,
                    server = "",
                    port = 0,
                    outboundJson = trimmed,
                )
                ProfileLinkParser.ImportResult(listOf(profile), emptyList())
            } catch (e: Exception) {
                ProfileLinkParser.ImportResult(
                    emptyList(),
                    listOf("invalid sing-box JSON: ${e.message}"),
                )
            }
        }
        return ProfileLinkParser.parseText(trimmed)
    }

    private fun Profile.toEntity() = profileToEntity(this)

    private fun ProfileEntity.toModel() = profileEntityToModel(this)
}

/**
 * Shared Profile ↔ entity mapping (module-internal so both
 * [ProfileRepository] and
 * [SubscriptionRepository][dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository]
 * store profiles the same way).
 *
 * The JSON backup format deliberately does NOT carry `subscriptionId`:
 * an exported profile re-imports as a manual profile, so a backup
 * restored on another device never points at a subscription that does
 * not exist there.
 */
internal fun profileToEntity(profile: Profile) = ProfileEntity(
    id = profile.id,
    name = profile.name,
    type = profile.type.name,
    server = profile.server,
    port = profile.port,
    outboundJson = profile.outboundJson,
    createdAt = profile.createdAt,
    countryCode = profile.countryCode,
    subscriptionId = profile.subscriptionId,
)

/**
 * Entity → model. Returns `null` for a row whose stored `type` is not a
 * known [ProfileType] (hand-edited database, or a row written by an older
 * build) so one corrupt row can never crash the whole profile list —
 * callers skip it instead.
 */
internal fun profileEntityToModel(entity: ProfileEntity): Profile? = try {
    Profile(
        id = entity.id,
        name = entity.name,
        type = ProfileType.valueOf(entity.type),
        server = entity.server,
        port = entity.port,
        outboundJson = entity.outboundJson,
        createdAt = entity.createdAt,
        countryCode = entity.countryCode,
        subscriptionId = entity.subscriptionId,
    )
} catch (e: IllegalArgumentException) {
    null
}
