package dev.aalhdydy550.tunnel.core.data.profile

import okhttp3.OkHttpClient
import okhttp3.Request
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Fetches a subscription URL and parses it into profiles.
 *
 * A subscription is a URL that serves a list of proxy links — either
 * plain text (one link per line) or base64 of that same list (the
 * standard subscription format). The fetched text goes through the
 * same [ProfileLinkParser] as a manual paste, so the import rules are
 * identical; a subscription never adds a profile format the manual
 * import cannot already read.
 *
 * Pure Kotlin (OkHttp only): unit-tested on the JVM against a local
 * HTTP server.
 */
object SubscriptionFetcher {

    /** Refuse bodies larger than this: a link list is small text. */
    const val MAX_BODY_BYTES = 1_000_000

    /** Downloads the subscription body as text. Throws on any failure. */
    fun fetchBody(
        client: OkHttpClient,
        url: String,
        timeoutMs: Long = 15_000,
    ): String {
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "subscription URL must start with http:// or https://"
        }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "AymanTunnelPlus")
            .build()
        client.newBuilder()
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .build()
            .newCall(request).execute().use { response ->
                require(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body
                    ?: throw IllegalStateException("empty subscription body")
                val bytes = body.byteStream().use { input ->
                    readCapped(input)
                }
                require(bytes.size <= MAX_BODY_BYTES) {
                    "subscription body too large (${bytes.size} bytes)"
                }
                return String(bytes, StandardCharsets.UTF_8)
            }
    }

    /** Reads at most [MAX_BODY_BYTES] + 1 bytes (the +1 detects overflow). */
    private fun readCapped(input: java.io.InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BODY_BYTES) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    /**
     * Decodes a subscription body to a plain link list.
     *
     * A body that already contains `://` is plain text and returned
     * as-is. Otherwise it is tried as base64 (standard and URL-safe
     * alphabets, missing padding tolerated): when it decodes to
     * readable text, that text is the list. Anything else is returned
     * unchanged so the parser reports it as errors instead of the
     * fetcher guessing.
     */
    fun decodeBody(body: String): String {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || "://" in trimmed) return trimmed
        val compact = trimmed.replace("\\s".toRegex(), "")
        val normalized = compact.replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        val decoded = runCatching {
            String(Base64.getDecoder().decode(padded), StandardCharsets.UTF_8)
        }.getOrNull() ?: return trimmed
        // Binary garbage decodes to replacement characters — not a list.
        if ('�' in decoded || decoded.isBlank()) return trimmed
        return decoded
    }

    /** Decodes a body and parses it as proxy links. */
    fun parseSubscriptionBody(body: String): ProfileLinkParser.ImportResult =
        ProfileLinkParser.parseText(decodeBody(body))

    /** Fetches a subscription URL and parses it. Throws on fetch failure. */
    fun fetchAndParse(
        client: OkHttpClient,
        url: String,
    ): ProfileLinkParser.ImportResult = parseSubscriptionBody(fetchBody(client, url))
}
