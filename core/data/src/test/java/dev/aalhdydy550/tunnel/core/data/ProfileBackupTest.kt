package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.backup.exportProfilesToJson
import dev.aalhdydy550.tunnel.core.data.backup.importProfilesFromJson
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.model.flagEmojiFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the JSON backup format and the flag emoji helper:
 * export → import must round-trip every field, and bad entries must be
 * reported instead of aborting the whole import.
 */
class ProfileBackupTest {

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

    @Test
    fun `export then import round-trips every field`() {
        val original = sampleProfiles()
        val json = exportProfilesToJson(original)
        val result = importProfilesFromJson(json)
        assertTrue(result.errors.isEmpty())
        assertEquals(2, result.profiles.size)
        assertEquals("Germany 1", result.profiles[0].name)
        assertEquals(ProfileType.VLESS, result.profiles[0].type)
        assertEquals("DE", result.profiles[0].countryCode)
        assertEquals("🇩🇪", result.profiles[0].flagEmoji)
        assertEquals("", result.profiles[1].countryCode)
        assertEquals("", result.profiles[1].flagEmoji)
        assertEquals("""{"type":"ssh"}""", result.profiles[1].outboundJson)
    }

    @Test
    fun `import skips bad entries and reports them`() {
        val json = """[
            {"name":"Good","type":"VLESS","server":"203.0.113.10","port":443,"outbound_json":"{}"},
            {"name":"Bad type","type":"NOPE","server":"","port":0,"outbound_json":"{}"},
            {"name":"","type":"VLESS","server":"","port":0,"outbound_json":"{}"}
        ]"""
        val result = importProfilesFromJson(json)
        assertEquals(1, result.profiles.size)
        assertEquals("Good", result.profiles[0].name)
        assertEquals(2, result.errors.size)
    }

    @Test
    fun `import of invalid json returns one error`() {
        val result = importProfilesFromJson("{not json")
        assertTrue(result.profiles.isEmpty())
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `flag emoji only for valid two-letter codes`() {
        assertEquals("🇪🇬", flagEmojiFor("eg"))
        assertEquals("🇺🇸", flagEmojiFor(" US "))
        assertEquals("", flagEmojiFor(""))
        assertEquals("", flagEmojiFor("DEU"))
        assertEquals("", flagEmojiFor("D1"))
    }
}
