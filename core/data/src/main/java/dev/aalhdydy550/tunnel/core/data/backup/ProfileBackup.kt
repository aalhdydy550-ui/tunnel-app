package dev.aalhdydy550.tunnel.core.data.backup

import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Clean JSON export / import of the whole profile list.
 *
 * This replaces the original app's opaque MMKV stores (7 stores, encoded
 * keys, hand-written encode/decode): the backup is a plain JSON array
 * the user can read, copy, and re-import anywhere. One entry per
 * profile — name, type, server, port, country code, and the sing-box
 * outbound JSON. No credentials are added by the app; the outbound
 * JSON contains exactly what the user imported (a password inside a
 * profile is the user's own server password, never a demo account —
 * the app ships no embedded credentials at all).
 *
 * Pure Kotlin: unit-tested on the JVM.
 */
private val json = Json { ignoreUnknownKeys = true }

fun exportProfilesToJson(profiles: List<Profile>): String {
    return buildJsonArray {
        profiles.forEach { profile ->
            add(
                buildJsonObject {
                    put("name", profile.name)
                    put("type", profile.type.name)
                    put("server", profile.server)
                    put("port", profile.port)
                    put("country_code", profile.countryCode)
                    put("outbound_json", profile.outboundJson)
                },
            )
        }
    }.toString()
}

/**
 * Parses a JSON array produced by [exportProfilesToJson]. Entries that
 * fail validation are reported in [ProfileLinkParser.ImportResult.errors]
 * and skipped; valid entries are returned as profiles.
 */
fun importProfilesFromJson(text: String): ProfileLinkParser.ImportResult {
    val profiles = mutableListOf<Profile>()
    val errors = mutableListOf<String>()
    val array = try {
        json.parseToJsonElement(text).jsonArray
    } catch (e: Exception) {
        return ProfileLinkParser.ImportResult(
            emptyList(),
            listOf("invalid backup JSON: ${e.message}"),
        )
    }
    for ((index, element) in array.withIndex()) {
        try {
            profiles += parseExportedProfile(element.jsonObject)
        } catch (e: Exception) {
            errors += "entry ${index + 1}: ${e.message}"
        }
    }
    return ProfileLinkParser.ImportResult(profiles, errors)
}

private fun parseExportedProfile(obj: JsonObject): Profile {
    val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
    val typeName = obj["type"]?.jsonPrimitive?.content?.trim().orEmpty()
    val outboundJson = (obj["outbound_json"] ?: obj["outboundJson"])
        ?.jsonPrimitive?.content.orEmpty()
    require(name.isNotBlank()) { "missing name" }
    val type = try {
        ProfileType.valueOf(typeName)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("unknown type: $typeName")
    }
    require(outboundJson.isNotBlank()) { "missing outbound_json" }
    return Profile(
        name = name,
        type = type,
        server = obj["server"]?.jsonPrimitive?.content.orEmpty(),
        port = obj["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
        outboundJson = outboundJson,
        countryCode = obj["country_code"]?.jsonPrimitive?.content.orEmpty(),
    )
}
