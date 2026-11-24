package dev.aalhdydy550.tunnel.core.data.backup

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.nio.charset.StandardCharsets

/**
 * QR generation with ZXing core (Apache-2.0, pure Java) — the
 * encode side of the locked export (item 4, §4 in the better-app
 * round plan), next to [dev.aalhdydy550.tunnel.core.data.profile.QrCodeDecoder].
 *
 * The QR carries one locked profile at a time as an `aytun://v1.…`
 * link (see `LockedExport`): a whole encrypted profile list will
 * usually exceed the QR capacity, so the caller must fall back to
 * sharing the `.aytun` file instead — [encode] enforces that with
 * [MAX_QR_BYTES] rather than emitting a damaged code.
 *
 * Pure Kotlin/JVM: no Android dependency, so this is unit-tested on
 * the JVM (see `QrCodeEncoderTest`). The Android side (pixel array
 * → Bitmap) is a thin adapter in `:feature:profiles`.
 */
object QrCodeEncoder {

    /**
     * Maximum QR payload in UTF-8 bytes: the binary capacity of a
     * version-40 QR code at error-correction level M. The plan
     * quoted "~3KB", which is the level-L figure; M was chosen
     * instead for scan robustness (a code shown on one phone and
     * scanned by another is often read at an angle, in poor light,
     * or off a scratched screen), and a single locked profile is
     * about 1KB anyway, so the tighter cap costs nothing in
     * practice.
     */
    const val MAX_QR_BYTES = 2331

    /** ARGB pixels of a QR code: black modules on a white background. */
    data class QrImage(val pixels: IntArray, val size: Int)

    /**
     * Renders [text] as a QR code of [size]×[size] pixels.
     *
     * @throws IllegalArgumentException when [text] is blank, when its
     * UTF-8 form exceeds [MAX_QR_BYTES], or when [size] is not
     * positive. All messages are Arabic — they are shown to the user.
     */
    fun encode(text: String, size: Int = 512): QrImage {
        require(size > 0) { "حجم رمز QR يجب أن يكون أكبر من صفر" }
        if (text.isBlank()) {
            throw IllegalArgumentException("النص فارغ — لا يمكن توليد رمز QR لنص فارغ")
        }
        val byteCount = text.toByteArray(StandardCharsets.UTF_8).size
        if (byteCount > MAX_QR_BYTES) {
            throw IllegalArgumentException(
                "النص أكبر من سعة رمز QR — شارك الملف بدلًا منه",
            )
        }
        // UTF-8 hint is mandatory: without it ZXing falls back to
        // ISO-8859-1 and replaces characters outside it (for
        // example "—") with '?', so the decoded text would not
        // round-trip the input (CI-fix round lesson).
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return QrImage(pixels, size)
    }
}
