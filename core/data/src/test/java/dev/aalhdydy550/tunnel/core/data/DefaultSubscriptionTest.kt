package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.profile.DefaultSubscription
import dev.aalhdydy550.tunnel.core.data.profile.SubscriptionFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [DefaultSubscription] — the built-in default free
 * subscription. Pure Kotlin, no network: the URL is only ever fetched
 * on the user's phone, never here.
 */
class DefaultSubscriptionTest {

    @Test
    fun `default URL is an allowed https subscription URL`() {
        assertTrue(DefaultSubscription.DEFAULT_URL.startsWith("https://"))
        assertTrue(
            SubscriptionFetcher.isAllowedSubscriptionUrl(DefaultSubscription.DEFAULT_URL),
        )
    }

    @Test
    fun `default URL points at the public aggregator list`() {
        assertEquals(
            "https://raw.githubusercontent.com/mahdibland/ShadowsocksAggregator/master/Eternity.txt",
            DefaultSubscription.DEFAULT_URL,
        )
    }

    @Test
    fun `default name is the public free-servers label`() {
        assertEquals("سيرفرات مجانية عامة", DefaultSubscription.DEFAULT_NAME)
    }

    @Test
    fun `resolveUrl falls back to the default for blank input`() {
        assertEquals(DefaultSubscription.DEFAULT_URL, DefaultSubscription.resolveUrl(null))
        assertEquals(DefaultSubscription.DEFAULT_URL, DefaultSubscription.resolveUrl(""))
        assertEquals(DefaultSubscription.DEFAULT_URL, DefaultSubscription.resolveUrl("   "))
    }

    @Test
    fun `resolveUrl keeps a configured URL, trimmed`() {
        assertEquals(
            "https://example.com/my-list.txt",
            DefaultSubscription.resolveUrl("  https://example.com/my-list.txt  "),
        )
    }

    @Test
    fun `public warning says free, slow and not for logins or banking`() {
        val warning = DefaultSubscription.PUBLIC_WARNING
        assertTrue(warning.isNotBlank())
        assertTrue(warning.contains("مجانية"))
        assertTrue(warning.contains("بطيئة"))
        assertTrue(warning.contains("تسجيل الدخول"))
        assertTrue(warning.contains("البنوك"))
    }
}
