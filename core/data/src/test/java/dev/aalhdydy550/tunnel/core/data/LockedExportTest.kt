package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.backup.LockedExport
import dev.aalhdydy550.tunnel.core.data.backup.LockedExport.UnlockResult
import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.backup.importProfilesFromJson
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

/**
 * Unit tests for the locked `aytun://v1` export format: full round-trip
 * through the real JSON backup, wrong password, tampered payload (GCM
 * tag), empty password, unknown version, fresh salt/nonce per export,
 * and plain (non-locked) text.
 */
class LockedExportTest {

    private fun sampleProfiles() = listOf(
        Profile(
            name = "Germany 1",
            type = ProfileType.VLESS,
            server = "203.0.113.10",
            port = 443,
            outboundJson = """{"type":"vless"}""",
            countryCode = "DE",
        ),
        Profile(
            name = "SSH box",
            type = ProfileType.SSH,
            server = "203.0.113.30",
            port = 22,
            outboundJson = """{"type":"ssh"}""",
        ),
    )

    private fun payloadOf(locked: String): ByteArray {
        val encoded = locked.substringAfter("aytun://v1.")
        return Base64.getUrlDecoder().decode(encoded)
    }

    private fun relockWithPayload(payload: ByteArray): String {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        return "aytun://v1.$encoded"
    }

    @Test
    fun `lock then unlock round-trips real backup json`() {
        val json = exportProfilesToJson(sampleProfiles())
        val locked = LockedExport.lock(json, "s3cret-pass".toCharArray())
        assertTrue(LockedExport.isLockedText(locked))
        assertTrue(locked.startsWith("aytun://v1."))

        val result = LockedExport.unlock(locked, "s3cret-pass".toCharArray())
        assertTrue(result is UnlockResult.Ok)
        val unlocked = (result as UnlockResult.Ok).json
        assertEquals(json, unlocked)

        // The decrypted text is a plain backup again.
        val imported = importProfilesFromJson(unlocked)
        assertTrue(imported.errors.isEmpty())
        assertEquals(2, imported.profiles.size)
        assertEquals("Germany 1", imported.profiles[0].name)
        assertEquals(ProfileType.SSH, imported.profiles[1].type)
    }

    @Test
    fun `wrong password fails with clear message`() {
        val json = exportProfilesToJson(sampleProfiles())
        val locked = LockedExport.lock(json, "correct-horse".toCharArray())

        val result = LockedExport.unlock(locked, "wrong-password".toCharArray())
        assertTrue(result is UnlockResult.Error)
        assertEquals(
            "كلمة السر غير صحيحة أو الملف تالف",
            (result as UnlockResult.Error).message,
        )
    }

    @Test
    fun `flipping one payload byte fails the gcm tag`() {
        val json = exportProfilesToJson(sampleProfiles())
        val locked = LockedExport.lock(json, "s3cret-pass".toCharArray())

        // Flip the last ciphertext byte: the GCM tag must reject it.
        val payload = payloadOf(locked)
        payload[payload.size - 1] = (payload[payload.size - 1].toInt() xor 0x01).toByte()
        val tampered = relockWithPayload(payload)

        val result = LockedExport.unlock(tampered, "s3cret-pass".toCharArray())
        assertTrue(result is UnlockResult.Error)
        assertEquals(
            "كلمة السر غير صحيحة أو الملف تالف",
            (result as UnlockResult.Error).message,
        )
    }

    @Test
    fun `empty password is rejected on lock`() {
        try {
            LockedExport.lock("[]", "".toCharArray())
            fail("expected IllegalArgumentException for an empty password")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `blank password is rejected on lock`() {
        try {
            LockedExport.lock("[]", "   ".toCharArray())
            fail("expected IllegalArgumentException for a blank password")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `empty json is rejected on lock`() {
        try {
            LockedExport.lock("  ", "s3cret-pass".toCharArray())
            fail("expected IllegalArgumentException for empty JSON")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `unknown version is rejected`() {
        val json = exportProfilesToJson(sampleProfiles())
        val locked = LockedExport.lock(json, "s3cret-pass".toCharArray())
        val v2 = locked.replace("aytun://v1.", "aytun://v2.")

        val result = LockedExport.unlock(v2, "s3cret-pass".toCharArray())
        assertTrue(result is UnlockResult.Error)
        assertEquals(
            "إصدار صيغة غير مدعوم",
            (result as UnlockResult.Error).message,
        )
    }

    @Test
    fun `same input locks to different outputs with different salt and nonce`() {
        val json = exportProfilesToJson(sampleProfiles())
        val first = LockedExport.lock(json, "s3cret-pass".toCharArray())
        val second = LockedExport.lock(json, "s3cret-pass".toCharArray())
        assertNotEquals(first, second)

        // Salt is payload bytes 6..22, nonce is bytes 22..34.
        val firstPayload = payloadOf(first)
        val secondPayload = payloadOf(second)
        assertFalse(
            firstPayload.copyOfRange(6, 22)
                .contentEquals(secondPayload.copyOfRange(6, 22)),
        )
        assertFalse(
            firstPayload.copyOfRange(22, 34)
                .contentEquals(secondPayload.copyOfRange(22, 34)),
        )
    }

    @Test
    fun `plain text is not a locked export`() {
        assertFalse(LockedExport.isLockedText(exportProfilesToJson(sampleProfiles())))
        assertFalse(LockedExport.isLockedText(""))

        val result = LockedExport.unlock(
            exportProfilesToJson(sampleProfiles()),
            "s3cret-pass".toCharArray(),
        )
        assertTrue(result is UnlockResult.Error)
        assertEquals(
            "هذا ليس تصديرًا مقفولًا صالحًا",
            (result as UnlockResult.Error).message,
        )
    }
}
