package dev.aalhdydy550.tunnel.core.data.backup

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Locked (password-protected) export of the profile list — our own
 * `aytun://v1` format.
 *
 * Text form: `aytun://v1.<base64url>` where the binary payload is:
 *
 * ```
 * magic (6 bytes, "AYTUN1") | salt (16 bytes, random)
 * | nonce (12 bytes, random) | ciphertext (AES-256-GCM, 128-bit tag)
 * ```
 *
 * The ciphertext is the very JSON document produced by
 * [exportProfilesToJson] — the JSON structure is unchanged, so a locked
 * export decrypts back to a plain backup that [importProfilesFromJson]
 * reads as-is. The password is never stored; a wrong password or a
 * tampered payload fails the GCM tag check. Losing the password means
 * losing the export for good — there is no recovery.
 *
 * Pure Kotlin + `javax.crypto`: unit-tested on the JVM, no Android
 * dependency, no `kotlinx.serialization` in this file.
 */
object LockedExport {

    /** PBKDF2-HMAC-SHA256 iterations (see [deriveKey]). */
    const val PBKDF2_ITERATIONS = 210_000

    private const val PREFIX = "aytun://"
    private const val VERSION = "v1"
    private const val SCHEME = "$PREFIX$VERSION."

    private val MAGIC = "AYTUN1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LENGTH_BYTES = 16
    private const val NONCE_LENGTH_BYTES = 12
    private const val KEY_LENGTH_BITS = 256
    private const val GCM_TAG_LENGTH_BITS = 128

    /** Minimum payload size: magic + salt + nonce + GCM tag. */
    private val MIN_PAYLOAD_BYTES =
        MAGIC.size + SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES + GCM_TAG_LENGTH_BITS / 8

    sealed interface UnlockResult {
        data class Ok(val json: String) : UnlockResult
        data class Error(val message: String) : UnlockResult
    }

    /** True when [text] looks like a locked export in our format. */
    fun isLockedText(text: String): Boolean = text.trim().startsWith(SCHEME)

    /**
     * Encrypts [json] under [password] and returns the `aytun://v1.…`
     * text. A fresh random salt and nonce are generated on every call,
     * so locking the same input twice gives different outputs.
     *
     * The password array is wiped (filled with NUL) before returning,
     * on success and on failure alike.
     *
     * @throws IllegalArgumentException when the password is empty or
     * only whitespace, or when [json] is empty.
     */
    fun lock(json: String, password: CharArray): String {
        require(json.isNotBlank()) { "لا يمكن تصدير ملف فارغ" }
        require(password.isNotEmpty() && password.any { !it.isWhitespace() }) { "كلمة السر فارغة" }
        try {
            val salt = ByteArray(SALT_LENGTH_BYTES)
            val nonce = ByteArray(NONCE_LENGTH_BYTES)
            SecureRandom().apply {
                nextBytes(salt)
                nextBytes(nonce)
            }
            val keyBytes = deriveKey(password, salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.ENCRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
                )
                val ciphertext = cipher.doFinal(json.toByteArray(Charsets.UTF_8))
                val payload = MAGIC + salt + nonce + ciphertext
                val encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(payload)
                return "$SCHEME$encoded"
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Decrypts a locked export [text] with [password].
     *
     * Errors are plain Arabic with no technical detail: an unknown
     * format, an unsupported version, and a wrong password / corrupt
     * payload (base64, structure, or GCM tag failure) each map to one
     * fixed message. The password array is wiped before returning.
     */
    fun unlock(text: String, password: CharArray): UnlockResult {
        try {
            val trimmed = text.trim()
            if (!trimmed.startsWith(PREFIX)) {
                return UnlockResult.Error("هذا ليس تصديرًا مقفولًا صالحًا")
            }
            val rest = trimmed.removePrefix(PREFIX)
            val dot = rest.indexOf('.')
            if (dot <= 0) {
                return UnlockResult.Error("هذا ليس تصديرًا مقفولًا صالحًا")
            }
            if (rest.substring(0, dot) != VERSION) {
                return UnlockResult.Error("إصدار صيغة غير مدعوم")
            }
            val payload = try {
                Base64.getUrlDecoder().decode(rest.substring(dot + 1))
            } catch (e: IllegalArgumentException) {
                return UnlockResult.Error("كلمة السر غير صحيحة أو الملف تالف")
            }
            if (payload.size < MIN_PAYLOAD_BYTES ||
                !payload.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)
            ) {
                return UnlockResult.Error("كلمة السر غير صحيحة أو الملف تالف")
            }
            val salt = payload.copyOfRange(
                MAGIC.size,
                MAGIC.size + SALT_LENGTH_BYTES,
            )
            val nonce = payload.copyOfRange(
                MAGIC.size + SALT_LENGTH_BYTES,
                MAGIC.size + SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES,
            )
            val ciphertext = payload.copyOfRange(
                MAGIC.size + SALT_LENGTH_BYTES + NONCE_LENGTH_BYTES,
                payload.size,
            )
            val keyBytes = deriveKey(password, salt)
            try {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(GCM_TAG_LENGTH_BITS, nonce),
                )
                val plain = try {
                    cipher.doFinal(ciphertext)
                } catch (e: Exception) {
                    // Wrong password or tampered payload: the GCM tag
                    // check fails. One message for both — never leak
                    // which one it was.
                    return UnlockResult.Error("كلمة السر غير صحيحة أو الملف تالف")
                }
                return UnlockResult.Ok(String(plain, Charsets.UTF_8))
            } finally {
                keyBytes.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * PBKDF2-HMAC-SHA256 → 256-bit AES key.
     *
     * 210,000 iterations: the plan's 600,000 measured ~700–800 ms per
     * derivation in steady state on this x86 VM, which estimates to
     * ~3–5 s on the user's Infinix Smart 5 (Cortex-A53, 2 GB Go) —
     * over the ~3 s budget for opening an export — so the count was
     * lowered to 210,000 now and the deviation is recorded in the
     * item-4 report instead of shipping a slow unlock.
     */
    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
