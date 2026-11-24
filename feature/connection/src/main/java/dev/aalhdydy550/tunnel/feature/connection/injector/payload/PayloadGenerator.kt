package dev.aalhdydy550.tunnel.feature.connection.injector.payload

/**
 * Builds the exact bytes of one HTTP injection request from a
 * [PayloadTemplate] plus the run-time values.
 *
 * Wire format (structured templates): the request line, then the
 * headers in fixed order (`Host:` first), then the blank line —
 * everything CRLF-terminated, exactly as a proxy expects:
 *
 * ```
 * CONNECT ssh.example.com:443 HTTP/1.1\r\n
 * Host: ssh.example.com\r\n
 * \r\n
 * ```
 *
 * **Header-injection guard (tested explicitly):** the substituted
 * values (`{host}` / `{port}` / `{sni}`) are stripped of CR and LF
 * before substitution, and every emitted header name/value is
 * stripped of CR/LF again after substitution — so a hostile or
 * mistyped host can never smuggle an extra header line into the
 * request. The template's own structure is trusted (built-ins are
 * ours; a custom template is text the user wrote themselves), but
 * the *values* flowing into it never are.
 *
 * Pure Kotlin, no Android dependency, unit-tested on the JVM.
 */
object PayloadGenerator {

    const val PLACEHOLDER_HOST = "{host}"
    const val PLACEHOLDER_PORT = "{port}"
    const val PLACEHOLDER_SNI = "{sni}"

    /**
     * Strips CR and LF from a substituted value (and trims it).
     * Applied to every value before it is placed into a request —
     * a value containing a newline can never become a header line.
     */
    fun sanitize(value: String): String =
        value.replace("\r", "").replace("\n", "").trim()

    /** Builds the request bytes (UTF-8; request lines are ASCII). */
    fun build(
        template: PayloadTemplate,
        host: String,
        port: Int,
        sniHost: String,
    ): ByteArray = buildText(template, host, port, sniHost)
        .toByteArray(Charsets.UTF_8)

    /**
     * Builds the request text. Throws [IllegalArgumentException]
     * (Arabic message) on a blank host, an out-of-range port, a
     * `{sni}` placeholder with no SNI host, or an empty custom text.
     */
    fun buildText(
        template: PayloadTemplate,
        host: String,
        port: Int,
        sniHost: String,
    ): String {
        require(host.isNotBlank()) { "هوست الهدف فارغ — لا يمكن بناء الحمولة" }
        require(port in 1..65535) { "المنفذ خارج النطاق: $port" }
        val cleanHost = sanitize(host)
        val cleanSni = sanitize(sniHost)
        return if (template.isCustom) {
            buildCustomText(template, cleanHost, port, cleanSni)
        } else {
            buildStructuredText(template, cleanHost, port, cleanSni)
        }
    }

    private fun buildStructuredText(
        template: PayloadTemplate,
        host: String,
        port: Int,
        sniHost: String,
    ): String {
        require(template.target.isNotBlank()) {
            "قالب بلا هدف — لا يمكن بناء الحمولة"
        }
        val target = substitute(template.target, host, port, sniHost)
        require(!template.usesSniPlaceholder() || sniHost.isNotBlank()) {
            "القالب يستخدم {sni} بلا هوست SNI"
        }
        require(target.isNotBlank()) { "هدف الطلب فارغ بعد الاستبدال" }
        // Headers, substituted + sanitised, with Host: first.
        val headers = template.headers.map { header ->
            val name = sanitize(substitute(header.name, host, port, sniHost))
            val value = sanitize(substitute(header.value, host, port, sniHost))
            PayloadHeader(name, value)
        }.filter { it.name.isNotBlank() }
        val ordered = headers.partition { it.name.equals("Host", ignoreCase = true) }
        val orderedHeaders = ordered.first + ordered.second
        return buildString {
            append(template.method.name)
            append(' ')
            append(target)
            append(" HTTP/1.1\r\n")
            for (header in orderedHeaders) {
                append(header.name)
                append(": ")
                append(header.value)
                append("\r\n")
            }
            append("\r\n")
        }
    }

    /**
     * Custom template: the user's own text, with placeholders
     * substituted (sanitised values) and line endings normalised to
     * CRLF. The request is terminated by a blank line, as a proxy
     * requires; a missing terminator is added, never assumed.
     */
    private fun buildCustomText(
        template: PayloadTemplate,
        host: String,
        port: Int,
        sniHost: String,
    ): String {
        require(template.customText.isNotBlank()) {
            "نص القالب المخصص فارغ"
        }
        if (template.customText.contains(PLACEHOLDER_SNI)) {
            require(sniHost.isNotBlank()) {
                "القالب يستخدم {sni} بلا هوست SNI"
            }
        }
        val substituted = substitute(template.customText, host, port, sniHost)
            .replace("\u0000", "")
        val lines = substituted
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .dropLastWhile { it.isEmpty() }
        require(lines.isNotEmpty()) { "نص القالب المخصص فارغ" }
        return lines.joinToString("\r\n") + "\r\n\r\n"
    }

    private fun substitute(text: String, host: String, port: Int, sni: String): String =
        text
            .replace(PLACEHOLDER_HOST, host)
            .replace(PLACEHOLDER_PORT, port.toString())
            .replace(PLACEHOLDER_SNI, sni)

    private fun PayloadTemplate.usesSniPlaceholder(): Boolean =
        target.contains(PLACEHOLDER_SNI) ||
            headers.any {
                it.name.contains(PLACEHOLDER_SNI) || it.value.contains(PLACEHOLDER_SNI)
            }
}
