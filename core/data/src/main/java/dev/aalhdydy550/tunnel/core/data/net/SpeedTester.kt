package dev.aalhdydy550.tunnel.core.data.net

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Optional in-app download speed test.
 *
 * Downloads a fixed-size payload through the *current* network path
 * (through the tunnel when connected, direct otherwise) and reports the
 * measured throughput. The default endpoint is Cloudflare's public
 * speed-test downloader (`speed.cloudflare.com/__down?bytes=…`), which
 * serves exactly the requested number of bytes.
 *
 * The client and the URL are injectable so the test can run against a
 * local HTTP server in unit tests. No result is ever claimed without
 * measuring: this class IS the measurement.
 *
 * Call [test] from an IO dispatcher — it blocks while downloading.
 */
class SpeedTester(
    private val client: OkHttpClient = defaultClient(),
    private val downloadUrl: String = DEFAULT_URL,
) {
    data class Result(
        /** Measured bytes per second over the whole download. */
        val bytesPerSecond: Long,
        val bytesDownloaded: Long,
        val elapsedMs: Long,
    )

    fun test(): Result {
        val request = Request.Builder().url(downloadUrl).build()
        val start = System.nanoTime()
        var bytes = 0L
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "speed test HTTP ${response.code}" }
            val body = response.body ?: throw IllegalStateException("empty speed test body")
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes += read
                }
            }
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        val bytesPerSecond = if (elapsedMs > 0) bytes * 1_000 / elapsedMs else 0L
        return Result(bytesPerSecond, bytes, elapsedMs)
    }

    companion object {
        /** 10 MiB from Cloudflare's public speed-test endpoint. */
        const val DEFAULT_URL = "https://speed.cloudflare.com/__down?bytes=10000000"

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
