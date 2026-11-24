package dev.aalhdydy550.tunnel.feature.connection.injector

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for SNI-host validation ([validateSniHost]) and for the
 * [TlsSniSocketFactory] constructor gate: no socket is ever opened
 * for an invalid SNI host.
 *
 * Pure JVM logic tests: no network is touched — the factory is only
 * constructed, never asked to create a socket.
 */
class TlsSniValidationTest {

    @Test
    fun `accepts real zero-rated hosts`() {
        assertTrue(validateSniHost("scorm.ekb.eg"))
        assertTrue(validateSniHost("digital.gov.eg"))
        assertTrue(validateSniHost("www.example.com"))
        assertTrue(validateSniHost("a-b.example-domain.co.uk"))
    }

    @Test
    fun `accepts a single-label hostname`() {
        assertTrue(validateSniHost("localhost"))
    }

    @Test
    fun `rejects blank hosts`() {
        assertFalse(validateSniHost(""))
        assertFalse(validateSniHost("   "))
    }

    @Test
    fun `rejects IP addresses`() {
        assertFalse(validateSniHost("192.168.1.1"))
        assertFalse(validateSniHost("10.0.0.1"))
        assertFalse(validateSniHost("8.8.8.8"))
        assertFalse(validateSniHost("::1"))
        assertFalse(validateSniHost("2001:db8::1"))
    }

    @Test
    fun `rejects whitespace and illegal characters`() {
        assertFalse(validateSniHost("exa mple.com"))
        assertFalse(validateSniHost("exam_ple.com"))
        assertFalse(validateSniHost("example.com:443"))
        assertFalse(validateSniHost("example.com/path"))
        assertFalse(validateSniHost("exämple.com"))
        assertFalse(validateSniHost("example.com?x=1"))
    }

    @Test
    fun `rejects malformed labels`() {
        assertFalse(validateSniHost("-bad.com"))
        assertFalse(validateSniHost("bad-.com"))
        assertFalse(validateSniHost("bad..com"))
        assertFalse(validateSniHost(".bad.com"))
        assertFalse(validateSniHost("bad.com."))
        assertFalse(validateSniHost("-"))
        assertFalse(validateSniHost("."))
    }

    @Test
    fun `rejects overlong hosts and labels`() {
        // 254 characters total.
        assertFalse(validateSniHost("a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(62)))
        // One label of 64 characters.
        assertFalse(validateSniHost("a".repeat(64) + ".example.com"))
        // Exactly 253 characters is the DNS limit and is accepted.
        assertTrue(validateSniHost("a".repeat(63) + "." + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)))
    }

    @Test
    fun `factory constructor accepts a valid SNI host`() {
        // Construction only validates + stores; no socket is opened.
        TlsSniSocketFactory("scorm.ekb.eg")
    }

    @Test
    fun `factory constructor rejects an invalid SNI host before any socket`() {
        assertThrows(IllegalArgumentException::class.java) {
            TlsSniSocketFactory("192.168.1.1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TlsSniSocketFactory("")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TlsSniSocketFactory("bad host.example")
        }
    }
}
