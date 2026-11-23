package dev.aalhdydy550.tunnel.core.data.routing

import dev.aalhdydy550.tunnel.core.data.net.defaultOkHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Downloads and tracks the routing rule-sets in [dir] (the app-private
 * `rule-sets/` directory).
 *
 * v0.1 manages one file: the ads category set. The file is tiny
 * (8,175 bytes) and is downloaded once — on first run when online, or
 * when the user taps retry — then reused offline. A file older than
 * [MAX_AGE_MS] is re-downloaded on the next [ensureDownloaded] call;
 * a failed download never deletes a working older file, so the app
 * keeps routing with slightly stale data instead of breaking.
 *
 * The manager never throws from [status]; [ensureDownloaded] reports
 * failures through the returned status (FAILED) instead.
 */
class RoutingDataManager(
    private val dir: File,
    private val client: OkHttpClient = defaultClient(),
    private val adsUrl: String = RoutingDataCatalog.ADS_RULE_SET_URL,
) {
    companion object {
        /** Re-download data older than 30 days. */
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000

        /** Directory name under filesDir for downloaded rule-sets. */
        const val RULE_SETS_DIR_NAME = "rule-sets"

        /** The shared OkHttp configuration (15s connect / 30s read). */
        fun defaultClient(): OkHttpClient = defaultOkHttpClient()
    }

    private val adsFile: File
        get() = File(dir, RoutingDataCatalog.ADS_RULE_SET_FILE)

    fun status(): RoutingDataStatus {
        val file = adsFile
        return if (file.exists() && file.length() > 0) {
            RoutingDataStatus(RoutingDataState.READY, file.lastModified())
        } else {
            RoutingDataStatus(RoutingDataState.NOT_DOWNLOADED, null)
        }
    }

    /**
     * Downloads the ads set when it is missing or older than
     * [MAX_AGE_MS]. Returns the status afterwards. Never throws.
     */
    suspend fun ensureDownloaded(): RoutingDataStatus = withContext(Dispatchers.IO) {
        val current = status()
        if (current.state == RoutingDataState.READY) {
            val age = System.currentTimeMillis() - (current.downloadedAtMs ?: 0L)
            if (age < MAX_AGE_MS) return@withContext current
        }
        try {
            dir.mkdirs()
            val request = Request.Builder().url(adsUrl).build()
            client.newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body ?: throw IllegalStateException("empty body")
                val tmp = File(dir, "${RoutingDataCatalog.ADS_RULE_SET_FILE}.tmp")
                tmp.outputStream().use { out ->
                    body.byteStream().use { input -> input.copyTo(out) }
                }
                require(tmp.length() > 0) { "downloaded file is empty" }
                tmp.renameTo(adsFile)
            }
            status()
        } catch (e: Exception) {
            if (status().state == RoutingDataState.READY) {
                status()
            } else {
                RoutingDataStatus(RoutingDataState.FAILED, null)
            }
        }
    }
}
