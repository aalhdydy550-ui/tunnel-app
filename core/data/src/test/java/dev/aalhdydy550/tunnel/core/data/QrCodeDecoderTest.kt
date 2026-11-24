package dev.aalhdydy550.tunnel.core.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.profile.ProfileLinkParser
import dev.aalhdydy550.tunnel.core.data.profile.QrCodeDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [QrCodeDecoder] — QR codes are generated locally
 * with ZXing itself (no fixture files, no camera, no network), then
 * decoded back through the production path.
 *
 * Pure JVM tests (no Android, no Robolectric needed): the pixel
 * array is the same input the Android Bitmap adapter feeds the
 * decoder, so a pass here covers the decode logic end to end.
 */
class QrCodeDecoderTest {

    private val vlessLink =
        "vless://550e8400-e29b-41d4-a716-446655440000@203.0.113.10:443" +
            "?encryption=none&security=tls&sni=example.com#Test%20VLESS"

    /** Renders [text] as a QR code and returns its ARGB pixels + size. */
    private fun qrPixels(text: String, size: Int = 256): Pair<IntArray, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val pixels = IntArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                pixels[y * size + x] =
                    if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return pixels to size
    }

    private fun decodeQr(text: String): String? {
        val (pixels, size) = qrPixels(text)
        return QrCodeDecoder.decode(pixels, size, size)
    }

    @Test
    fun `decodes a locally generated QR of a vless link`() {
        assertEquals(vlessLink, decodeQr(vlessLink))
    }

    @Test
    fun `decoded vless link parses through the normal import path`() {
        val decoded = decodeQr(vlessLink)!!
        val profile = ProfileLinkParser.parseLink(decoded)
        assertEquals(ProfileType.VLESS, profile.type)
        assertEquals("Test VLESS", profile.name)
        assertEquals("203.0.113.10", profile.server)
        assertEquals(443, profile.port)
    }

    @Test
    fun `decoded text is accepted only when importable`() {
        assertTrue(QrCodeDecoder.isImportableQrText(decodeQr(vlessLink)!!))
        assertTrue(
            QrCodeDecoder.isImportableQrText(
                decodeQr("https://example.com/subscription.txt")!!,
            ),
        )
    }

    @Test
    fun `garbage text decodes but is rejected as not importable`() {
        val decoded = decodeQr("hello world — not a proxy link")!!
        assertEquals("hello world — not a proxy link", decoded)
        assertFalse(QrCodeDecoder.isImportableQrText(decoded))
        assertTrue(ProfileLinkParser.parseText(decoded).profiles.isEmpty())
        assertTrue(ProfileLinkParser.parseText(decoded).errors.isNotEmpty())
    }

    @Test
    fun `blank image decodes to null`() {
        val size = 256
        val white = IntArray(size * size) { 0xFFFFFFFF.toInt() }
        assertNull(QrCodeDecoder.decode(white, size, size))
    }

    @Test
    fun `empty and blank text is not importable`() {
        assertFalse(QrCodeDecoder.isImportableQrText(""))
        assertFalse(QrCodeDecoder.isImportableQrText("   \n  "))
    }

    @Test
    fun `undersized pixel array decodes to null instead of throwing`() {
        assertNull(QrCodeDecoder.decode(IntArray(10), 100, 100))
        assertNull(QrCodeDecoder.decode(IntArray(0), 0, 0))
    }
}
