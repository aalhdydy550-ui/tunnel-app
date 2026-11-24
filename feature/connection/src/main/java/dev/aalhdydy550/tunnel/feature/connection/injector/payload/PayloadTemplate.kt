package dev.aalhdydy550.tunnel.feature.connection.injector.payload

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * HTTP request method of a payload template.
 *
 * Only the method word is stored here — the full request line is
 * built by [PayloadGenerator] (`METHOD <target> HTTP/1.1`).
 */
enum class PayloadMethod {
    CONNECT,
    GET,
    POST,
}

/** One header line of a structured payload template. */
data class PayloadHeader(val name: String, val value: String)

/**
 * A payload template: the shape of one HTTP injection request.
 *
 * Structured templates carry a method, a request target and a header
 * list; the placeholders `{host}`, `{port}` and `{sni}` inside them
 * are substituted by [PayloadGenerator] at build time (target host =
 * the user's SSH server, `{sni}` = the zero-rated host of the
 * selected network).
 *
 * A custom template ([isCustom]) is free text the user wrote
 * themselves ([customText]); its method/target/headers are ignored —
 * the text is used as-is (after placeholder substitution and CRLF
 * normalisation, see [PayloadGenerator]).
 *
 * **No values from any closed-source app live here.** The built-ins
 * below are structure only (a method word + standard header names —
 * the same shapes every public HTTP-injection guide describes);
 * every host, port and SNI value comes from the app's own reference
 * table or from user input at build time.
 */
data class PayloadTemplate(
    val id: String,
    val nameAr: String,
    val method: PayloadMethod,
    val target: String,
    val headers: List<PayloadHeader>,
    val customText: String = "",
    val isCustom: Boolean = false,
) {

    /** Serialises this template to a JSON object (for [PayloadStore]). */
    fun toJson(): JsonObject = buildJsonObject {
        put("id", id)
        put("name", nameAr)
        put("method", method.name)
        put("target", target)
        putJsonArray("headers") {
            for (header in headers) {
                add(
                    buildJsonObject {
                        put("name", header.name)
                        put("value", header.value)
                    },
                )
            }
        }
        put("custom_text", customText)
        put("custom", isCustom)
    }

    companion object {
        const val CONNECT_HOST_ID = "connect-host"
        const val CONNECT_X_ONLINE_HOST_ID = "connect-x-online-host"
        const val GET_HOST_ID = "get-host"

        /**
         * The three built-in templates (plan §3): CONNECT + Host,
         * CONNECT + X-Online-Host, GET + Host. `Host:` is always
         * first — [PayloadGenerator] enforces that order even if a
         * stored template lists it later.
         */
        val BUILT_INS: List<PayloadTemplate> = listOf(
            PayloadTemplate(
                id = CONNECT_HOST_ID,
                nameAr = "CONNECT + Host",
                method = PayloadMethod.CONNECT,
                target = "{host}:{port}",
                headers = listOf(PayloadHeader("Host", "{host}")),
            ),
            PayloadTemplate(
                id = CONNECT_X_ONLINE_HOST_ID,
                nameAr = "CONNECT + X-Online-Host",
                method = PayloadMethod.CONNECT,
                target = "{host}:{port}",
                headers = listOf(
                    PayloadHeader("Host", "{host}"),
                    PayloadHeader("X-Online-Host", "{host}"),
                ),
            ),
            PayloadTemplate(
                id = GET_HOST_ID,
                nameAr = "GET + Host",
                method = PayloadMethod.GET,
                target = "http://{host}/",
                headers = listOf(PayloadHeader("Host", "{host}")),
            ),
        )

        /** Default template: plain CONNECT + Host. */
        val DEFAULT: PayloadTemplate = BUILT_INS.first { it.id == CONNECT_HOST_ID }

        fun builtIn(id: String): PayloadTemplate? =
            BUILT_INS.firstOrNull { it.id == id }

        /** A user-written custom template (free text, used as-is). */
        fun custom(id: String, nameAr: String, text: String): PayloadTemplate =
            PayloadTemplate(
                id = id,
                nameAr = nameAr,
                method = PayloadMethod.CONNECT,
                target = "",
                headers = emptyList(),
                customText = text,
                isCustom = true,
            )

        /** Parses a template back from [toJson]; null on malformed input. */
        fun fromJson(obj: JsonObject): PayloadTemplate? = runCatching {
            val id = obj["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return null
            val name = obj["name"]?.jsonPrimitive?.content.orEmpty()
            val method = runCatching {
                PayloadMethod.valueOf(
                    obj["method"]?.jsonPrimitive?.content ?: PayloadMethod.CONNECT.name,
                )
            }.getOrDefault(PayloadMethod.CONNECT)
            val target = obj["target"]?.jsonPrimitive?.content.orEmpty()
            val headers = obj["headers"]?.jsonArray?.mapNotNull { element ->
                val header = element.jsonObject
                val headerName = header["name"]?.jsonPrimitive?.content.orEmpty()
                val headerValue = header["value"]?.jsonPrimitive?.content.orEmpty()
                if (headerName.isBlank()) null else PayloadHeader(headerName, headerValue)
            }.orEmpty()
            val customText = obj["custom_text"]?.jsonPrimitive?.content.orEmpty()
            val isCustom = obj["custom"]?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: false
            PayloadTemplate(id, name, method, target, headers, customText, isCustom)
        }.getOrNull()

        /** Parses a JSON array of templates; malformed entries are skipped. */
        fun listFromJsonArray(array: JsonArray): List<PayloadTemplate> =
            array.mapNotNull { element ->
                runCatching { fromJson(element.jsonObject) }.getOrNull()
            }
    }
}
