package dev.aalhdydy550.tunnel.core.data.repository

import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.backup.importProfilesFromJson
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
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
        list.map { it.toModel() }
    }

    suspend fun getById(id: Long): Profile? = dao.getById(id)?.toModel()

    suspend fun add(profile: Profile): Long =
        dao.insert(profile.toEntity())

    suspend fun update(profile: Profile) {
        dao.update(profile.toEntity())
    }

    suspend fun delete(profile: Profile) {
        dao.deleteById(profile.id)
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
        if (trimmed.startsWith("[")) {
            return importProfilesFromJson(trimmed)
        }
        if ("[Interface]" in trimmed && "[Peer]" in trimmed) {
            return try {
                val profile = WireGuardConfParser.parseConf(trimmed, fileName)
                ProfileLinkParser.ImportResult(listOf(profile), emptyList())
            } catch (e: IllegalArgumentException) {
                ProfileLinkParser.ImportResult(emptyList(), listOf(e.message ?: "invalid WireGuard config"))
            }
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

    private fun Profile.toEntity() = ProfileEntity(
        id = id,
        name = name,
        type = type.name,
        server = server,
        port = port,
        outboundJson = outboundJson,
        createdAt = createdAt,
        countryCode = countryCode,
    )

    private fun ProfileEntity.toModel() = Profile(
        id = id,
        name = name,
        type = ProfileType.valueOf(type),
        server = server,
        port = port,
        outboundJson = outboundJson,
        createdAt = createdAt,
        countryCode = countryCode,
    )
}
