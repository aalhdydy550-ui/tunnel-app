package dev.aalhdydy550.tunnel.core.data.net

/**
 * DNS choice for the tunnel (UI over the existing [DnsSelector] engine).
 *
 * - [DnsPreset.AUTO] (the default) changes nothing: at connect time the
 *   engine still probes the public resolvers and uses the fastest, with
 *   the Cloudflare fallback — exactly the behaviour before this screen
 *   existed.
 * - Any other preset pins one resolver: its [DnsPreset.dotAddress] is
 *   written into the sing-box config instead of the probe winner.
 * - [DnsPreset.CUSTOM] pins a host the user typed; it is only valid
 *   when [validateCustomDnsHost] accepts it, otherwise [DnsPreference.dotAddress]
 *   is null and the caller falls back to the automatic probe.
 *
 * Pure Kotlin: no Android dependency, unit-tested on the JVM.
 */
enum class DnsPreset(
    val arabicName: String,
    /** Address shown under the name, or null for AUTO / CUSTOM. */
    val address: String?,
    /** DoT address written into the config, or null for AUTO / CUSTOM. */
    val dotAddress: String?,
) {
    AUTO("تلقائي — الأسرع عند الاتصال", null, null),
    CLOUDFLARE("Cloudflare", "1.1.1.1", "tls://1.1.1.1"),
    GOOGLE("Google", "8.8.8.8", "tls://8.8.8.8"),
    QUAD9("Quad9", "9.9.9.9", "tls://9.9.9.9"),
    ALIDNS("AliDNS", "223.5.5.5", "tls://223.5.5.5"),
    CUSTOM("مخصّص", null, null),
}

data class DnsPreference(
    val preset: DnsPreset = DnsPreset.AUTO,
    /** User-typed host for [DnsPreset.CUSTOM]; ignored otherwise. */
    val customHost: String = "",
) {
    /**
     * The DoT address to write into the config, or null when the engine
     * should keep its automatic behaviour (probe the fastest resolver).
     * Null also covers a CUSTOM choice whose host fails validation —
     * the caller then probes instead of writing a broken address.
     */
    val dotAddress: String?
        get() = when (preset) {
            DnsPreset.AUTO -> null
            DnsPreset.CUSTOM -> {
                val host = customHost.trim()
                if (validateCustomDnsHost(host) == null) "tls://$host" else null
            }
            else -> preset.dotAddress
        }
}

/**
 * Validates a user-typed DNS host (IPv4 or hostname). Returns null
 * when valid, otherwise an Arabic error message for the UI.
 *
 * Accepted: a dotted IPv4 address, or a hostname with at least two
 * labels (for example `dns.google`). Rejected: empty input, any
 * scheme (`https://…`), path, port (`:…`), whitespace, or a malformed
 * address. IPv6 literals are rejected — the known presets already
 * cover the common resolvers, and a bare `:` would be ambiguous with
 * a port.
 */
fun validateCustomDnsHost(rawHost: String): String? {
    val host = rawHost.trim()
    if (host.isEmpty()) {
        return "أدخل عنوان IP أو اسم نطاق — مثال: 9.9.9.9"
    }
    if (host.contains("://") || host.contains('/') || host.contains(':') ||
        host.any { it.isWhitespace() }
    ) {
        return "بدون بروتوكول أو منفذ أو مسافات — مثال: 9.9.9.9"
    }
    if (isValidIpv4(host) || isValidHostname(host)) return null
    return "عنوان غير صالح — أدخل IP صحيحًا أو اسم نطاق مثل dns.google"
}

private fun isValidIpv4(host: String): Boolean {
    val parts = host.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } &&
            // No leading zeros ("01" is ambiguous in classic inet parsing).
            part == part.toIntOrNull()?.toString() &&
            (part.toIntOrNull() ?: -1) in 0..255
    }
}

private fun isValidHostname(host: String): Boolean {
    if (host.length > 253) return false
    val labels = host.split('.')
    // At least two labels, so a bare word or a numeric dotted quad
    // cannot pass as a hostname; the last label must contain a letter.
    if (labels.size < 2) return false
    if (labels.last().none { it.isLetter() }) return false
    return labels.all { label ->
        label.length in 1..63 &&
            label.all { it.isLetterOrDigit() || it == '-' } &&
            !label.startsWith('-') && !label.endsWith('-')
    }
}
