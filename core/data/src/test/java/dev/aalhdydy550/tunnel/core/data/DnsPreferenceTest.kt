package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.net.DnsPreference
import dev.aalhdydy550.tunnel.core.data.net.DnsPreset
import dev.aalhdydy550.tunnel.core.data.net.validateCustomDnsHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Unit tests for the DNS preference model + custom-host validation. */
class DnsPreferenceTest {

    @Test
    fun `AUTO resolves to null so the engine keeps probing`() {
        assertNull(DnsPreference(DnsPreset.AUTO).dotAddress)
    }

    @Test
    fun `known presets resolve to their DoT addresses`() {
        assertEquals("tls://1.1.1.1", DnsPreference(DnsPreset.CLOUDFLARE).dotAddress)
        assertEquals("tls://8.8.8.8", DnsPreference(DnsPreset.GOOGLE).dotAddress)
        assertEquals("tls://9.9.9.9", DnsPreference(DnsPreset.QUAD9).dotAddress)
        assertEquals("tls://223.5.5.5", DnsPreference(DnsPreset.ALIDNS).dotAddress)
    }

    @Test
    fun `CUSTOM with a valid host resolves to tls host`() {
        assertEquals(
            "tls://9.9.9.9",
            DnsPreference(DnsPreset.CUSTOM, "9.9.9.9").dotAddress,
        )
        assertEquals(
            "tls://dns.google",
            DnsPreference(DnsPreset.CUSTOM, "dns.google").dotAddress,
        )
    }

    @Test
    fun `CUSTOM with an invalid host resolves to null (probe fallback)`() {
        assertNull(DnsPreference(DnsPreset.CUSTOM, "").dotAddress)
        assertNull(DnsPreference(DnsPreset.CUSTOM, "http://1.1.1.1").dotAddress)
        assertNull(DnsPreference(DnsPreset.CUSTOM, "999.1.1.1").dotAddress)
    }

    @Test
    fun `CUSTOM host is trimmed before use`() {
        assertEquals(
            "tls://9.9.9.9",
            DnsPreference(DnsPreset.CUSTOM, "  9.9.9.9  ").dotAddress,
        )
    }

    @Test
    fun `valid IPv4 hosts pass`() {
        assertNull(validateCustomDnsHost("1.1.1.1"))
        assertNull(validateCustomDnsHost("8.8.8.8"))
        assertNull(validateCustomDnsHost("0.0.0.0"))
        assertNull(validateCustomDnsHost("255.255.255.255"))
    }

    @Test
    fun `malformed IPv4 hosts fail`() {
        assertNotNull(validateCustomDnsHost("256.1.1.1"))
        assertNotNull(validateCustomDnsHost("1.2.3"))
        assertNotNull(validateCustomDnsHost("1.2.3.4.5"))
        assertNotNull(validateCustomDnsHost("01.2.3.4"))
        assertNotNull(validateCustomDnsHost("1.2.3.x"))
    }

    @Test
    fun `valid hostnames pass`() {
        assertNull(validateCustomDnsHost("dns.google"))
        assertNull(validateCustomDnsHost("one.one.one.one"))
        assertNull(validateCustomDnsHost("dns9.quad9.net"))
    }

    @Test
    fun `malformed hostnames fail`() {
        assertNotNull(validateCustomDnsHost("foo"))
        assertNotNull(validateCustomDnsHost("foo..bar"))
        assertNotNull(validateCustomDnsHost("-bad.example"))
        assertNotNull(validateCustomDnsHost("bad-.example"))
        assertNotNull(validateCustomDnsHost("example.123"))
        assertNotNull(validateCustomDnsHost(".example.com"))
        assertNotNull(validateCustomDnsHost("example.com."))
    }

    @Test
    fun `scheme port path and whitespace are rejected`() {
        assertNotNull(validateCustomDnsHost("https://dns.google"))
        assertNotNull(validateCustomDnsHost("tls://1.1.1.1"))
        assertNotNull(validateCustomDnsHost("1.1.1.1:853"))
        assertNotNull(validateCustomDnsHost("dns.google/dns-query"))
        assertNotNull(validateCustomDnsHost("foo bar"))
        assertNotNull(validateCustomDnsHost(""))
        assertNotNull(validateCustomDnsHost("   "))
    }
}
