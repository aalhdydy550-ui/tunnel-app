package dev.aalhdydy550.tunnel.core.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Failures of the release check. The ViewModel maps each one to an
 * Arabic message; the HTTP code is kept so 403/429 (GitHub's
 * unauthenticated rate limit: 60 requests/hour per IP) can be told
 * apart from a missing release (404) or a broken body.
 */
sealed class UpdateCheckError(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class HttpError(val code: Int) : UpdateCheckError("GitHub API returned HTTP $code")
    class BadResponse(message: String) : UpdateCheckError(message)
    class Network(cause: IOException) : UpdateCheckError("Network error: ${cause.message}", cause)
}

/**
 * Fetches the latest published release of this app from GitHub.
 *
 * Public endpoint, no auth, no token: `GET /releases/latest` never
 * returns drafts or pre-releases, so the answer is always the newest
 * stable release the user can install. The [apiUrl] parameter exists
 * only so tests can point the checker at a local stub server; callers
 * use the default.
 */
interface UpdateChecker {
    suspend fun checkLatest(apiUrl: String = LATEST_RELEASE_URL): Result<ReleaseInfo>
}

class GithubReleaseChecker(
    private val client: OkHttpClient,
) : UpdateChecker {

    override suspend fun checkLatest(apiUrl: String): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(apiUrl)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure<ReleaseInfo>(
                            UpdateCheckError.HttpError(response.code),
                        )
                    }
                    val body = response.body?.string()
                        ?: return@withContext Result.failure<ReleaseInfo>(
                            UpdateCheckError.BadResponse("Empty response body"),
                        )
                    Result.success(parseRelease(body))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateCheckError) {
                Result.failure(e)
            } catch (e: IOException) {
                Result.failure(UpdateCheckError.Network(e))
            } catch (e: Exception) {
                Result.failure(UpdateCheckError.BadResponse(e.message ?: "Bad response"))
            }
        }
}

/** Reads one optional string field; null when absent, JSON null, or not a string. */
private fun JsonObject.optString(key: String): String? {
    val element = this[key] ?: return null
    if (element is JsonNull) return null
    return try {
        element.jsonPrimitive.content
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Reads one optional array field; empty when absent or not an array. */
private fun JsonObject.optArray(key: String): JsonArray {
    val element = this[key] ?: return JsonArray(emptyList())
    return try {
        element.jsonArray
    } catch (_: IllegalArgumentException) {
        JsonArray(emptyList())
    }
}

/**
 * Parses a GitHub `/releases/latest` body into [ReleaseInfo].
 * Pure and JVM-testable (kotlinx.serialization.json, no Android).
 * Throws [UpdateCheckError.BadResponse] on malformed JSON or a
 * missing `tag_name`; assets missing a name or a download URL are
 * skipped, never fatal.
 */
fun parseRelease(json: String): ReleaseInfo {
    val root = try {
        Json.parseToJsonElement(json).jsonObject
    } catch (e: Exception) {
        throw UpdateCheckError.BadResponse("Invalid JSON: ${e.message}")
    }
    val tag = root.optString("tag_name")
        ?: throw UpdateCheckError.BadResponse("Missing tag_name")
    val assets = root.optArray("assets").mapNotNull { element ->
        val obj = try {
            element.jsonObject
        } catch (_: IllegalArgumentException) {
            null
        } ?: return@mapNotNull null
        val name = obj.optString("name") ?: return@mapNotNull null
        val downloadUrl = obj.optString("browser_download_url") ?: return@mapNotNull null
        val size = try {
            obj["size"]?.jsonPrimitive?.longOrNull
        } catch (_: IllegalArgumentException) {
            null
        } ?: 0L
        ReleaseAsset(name = name, sizeBytes = size, downloadUrl = downloadUrl)
    }
    return ReleaseInfo(
        tag = tag,
        name = root.optString("name"),
        publishedAt = root.optString("published_at"),
        assets = assets,
    )
}
