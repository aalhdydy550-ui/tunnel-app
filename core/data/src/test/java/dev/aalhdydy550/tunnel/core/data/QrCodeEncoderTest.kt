package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.backup.QrCodeEncoder
import dev.aalhdydy550.tunnel.core.data.profile.QrCodeDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Unit tests for [QrCodeEncoder] — codes are generated with the
 * production encoder and read back through the production decoder
 * ([QrCodeDecoder]), so a pass covers the encode→decode round trip
 * end to end. Pure JVM tests (no Android, no network).
 */
class QrCodeEncoderTest {

    /**
     * A sample locked-export link in the `aytun://v1.…` shape: the
     * base64url payload stands in for the real `LockedExport`
     * output (magic + salt + nonce + AES-256-GCM ciphertext of one
     * profile's JSON). Built here from a small JSON document so the
     * text resembles a real single-profile export (~1KB).
     */
    private fun sampleLockedExportText(): String {
        val json = """{"profiles":[{"name":"Test VLESS — خادم تجريبي","type":"VLESS","server":"203.0.113.10","port":443,"countryCode":"EG","outboundJson":"{\"type\":\"vless\",\"server\":\"203.0.113.10\",\"server_port\":443}"}]}"""
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(json.toByteArray(StandardCharsets.UTF_8))
        return "aytun://v1.$payload"
    }

    /** Asserts that [block] throws [IllegalArgumentException] with [expectedMessage]. */
    private fun assertThrowsWithMessage(expectedMessage: String, block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            assertEquals(expectedMessage, e.message)
            return
        }
        fail("Expected IllegalArgumentException with message: $expectedMessage")
    }

    @Test
    fun `encodes a locked export link and decodes it back verbatim`() {
        val text = sampleLockedExportText()
        assertTrue(text.toByteArray(StandardCharsets.UTF_8).size < QrCodeEncoder.MAX_QR_BYTES)
        val image = QrCodeEncoder.encode(text)
        assertEquals(512, image.size)
        assertEquals(512 * 512, image.pixels.size)
        val decoded = QrCodeDecoder.decode(image.pixels, image.size, image.size)
        assertEquals(text, decoded)
    }

    @Test
    fun `round-trips arabic text and em dash unchanged`() {
        // Guards the UTF-8 encode hint: without it ZXing falls back
        // to ISO-8859-1 and "—" comes back as '?'.
        val text = "aytun://v1.اختبار — شرطة طويلة وعربية"
        val image = QrCodeEncoder.encode(text, size = 256)
        assertEquals(text, QrCodeDecoder.decode(image.pixels, image.size, image.size))
    }

    @Test
    fun `pixels are black modules on a white background`() {
        val image = QrCodeEncoder.encode("aytun://v1.dGVzdA", size = 128)
        assertEquals(128 * 128, image.pixels.size)
        assertTrue(image.pixels.all { it == 0xFF000000.toInt() || it == 0xFFFFFFFF.toInt() })
        assertTrue(image.pixels.any { it == 0xFF000000.toInt() })
        assertTrue(image.pixels.any { it == 0xFFFFFFFF.toInt() })
    }

    @Test
    fun `text larger than the QR capacity is rejected with the capacity message`() {
        val tooBig = "a".repeat(QrCodeEncoder.MAX_QR_BYTES + 1)
        assertThrowsWithMessage(
            "النص أكبر من سعة رمز QR — شارك الملف بدلًا منه",
        ) { QrCodeEncoder.encode(tooBig) }
    }

    @Test
    fun `multi-byte text over the capacity in bytes is rejected even when short in chars`() {
        // "—" is 3 bytes in UTF-8, so 778 of them exceed 2331 bytes
        // while the string is only 778 characters long.
        val tooBig = "—".repeat(QrCodeEncoder.MAX_QR_BYTES / 3 + 1)
        assertTrue(tooBig.toByteArray(StandardCharsets.UTF_8).size > QrCodeEncoder.MAX_QR_BYTES)
        assertThrowsWithMessage(
            "النص أكبر من سعة رمز QR — شارك الملف بدلًا منه",
        ) { QrCodeEncoder.encode(tooBig) }
    }

    @Test
    fun `empty and blank text is rejected`() {
        assertThrowsWithMessage(
            "النص فارغ — لا يمكن توليد رمز QR لنص فارغ",
        ) { QrCodeEncoder.encode("") }
        assertThrowsWithMessage(
            "النص فارغ — لا يمكن توليد رمز QR لنص فارغ",
        ) { QrCodeEncoder.encode("   \n  ") }
    }
}
