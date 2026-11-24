package dev.aalhdydy550.tunnel.feature.connection.injector.payload

import android.content.Context
import dev.aalhdydy550.tunnel.feature.connection.injector.InjectorConstants
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persists host-scan results (see [HostScanner]).
 *
 * Results are a diagnostic log, not a promise: a stored SUCCESS only
 * ever meant "the proxy answered 200 at that time". New results are
 * merged on top of older ones (same host + proxy port replaces the
 * old row) and the log is capped at
 * [dev.aalhdydy550.tunnel.feature.connection.injector.InjectorConstants.MAX_SCAN_RESULTS]
 * rows, newest first — a 2 GB phone keeps no unbounded log.
 *
 * Same storage choice as [PayloadStore] (SharedPreferences + manual
 * `kotlinx.serialization.json` builders — see the note there):
 * `:feature:connection` has no DataStore dependency, and adding one
 * would mean touching `feature/connection/build.gradle.kts`, outside
 * this item's file scope.
 */
interface ScannedHostStore {

    /** Stored results, newest first. */
    fun results(): List<ScannedHost>

    /** Merges [results] on top of the stored ones (newest first, capped). */
    fun saveResults(results: List<ScannedHost>)

    fun clear()
}

/** [ScannedHostStore] backed by its own SharedPreferences file. */
class SharedPreferencesScannedHostStore(
    context: Context,
) : ScannedHostStore {

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private val json = Json { ignoreUnknownKeys = true }

    override fun results(): List<ScannedHost> {
        val raw = prefs.getString(RESULTS_KEY, null) ?: return emptyList()
        return runCatching {
            json.parseToJsonElement(raw).jsonArray.mapNotNull { element ->
                runCatching { scannedHostFromJson(element.jsonObject) }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    override fun saveResults(results: List<ScannedHost>) {
        val merged = (results + this.results())
            .distinctBy { it.host to it.proxyPort }
            .sortedByDescending { it.checkedAt }
            .take(InjectorConstants.MAX_SCAN_RESULTS)
        prefs.edit()
            .putString(RESULTS_KEY, resultsJson(merged))
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(RESULTS_KEY).apply()
    }

    private fun resultsJson(results: List<ScannedHost>): String =
        buildJsonArray {
            for (result in results) add(scannedHostToJson(result))
        }.toString()

    private fun scannedHostToJson(result: ScannedHost) = buildJsonObject {
        put("host", result.host)
        put("proxy_port", result.proxyPort)
        put("status", result.status.name)
        result.latencyMs?.let { put("latency_ms", it) }
        put("checked_at", result.checkedAt)
    }

    private fun scannedHostFromJson(obj: kotlinx.serialization.json.JsonObject): ScannedHost {
        val host = obj["host"]?.jsonPrimitive?.content.orEmpty()
        val proxyPort = obj["proxy_port"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val status = runCatching {
            ScanStatus.valueOf(obj["status"]?.jsonPrimitive?.content ?: "")
        }.getOrDefault(ScanStatus.FAILED)
        val latencyMs = obj["latency_ms"]?.jsonPrimitive?.content?.toLongOrNull()
        val checkedAt = obj["checked_at"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        return ScannedHost(host, proxyPort, status, latencyMs, checkedAt)
    }

    companion object {
        const val PREFS_NAME = "injector_scanned_hosts"
        private const val RESULTS_KEY = "results"
    }
}
