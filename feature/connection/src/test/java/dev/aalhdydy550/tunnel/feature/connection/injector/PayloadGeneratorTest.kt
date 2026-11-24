package dev.aalhdydy550.tunnel.feature.connection.injector

import dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadGenerator
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadHeader
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadMethod
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Byte-exact tests for [PayloadGenerator] — no network of any kind.
 *
 * The expected strings below are the literal wire format (CRLF
 * included): any drift in the request line, the header order or the
 * line endings fails loudly here instead of silently on the phone.
 */
class PayloadGeneratorTest {

    private val connectHost = PayloadTemplate.builtIn(PayloadTemplate.CONNECT_HOST_ID)!!
    private val connectXOnline =
        PayloadTemplate.builtIn(PayloadTemplate.CONNECT_X_ONLINE_HOST_ID)!!
    private val getHost = PayloadTemplate.builtIn(PayloadTemplate.GET_HOST_ID)!!

    @Test
    fun `built-ins are exactly three with unique ids`() {
        assertEquals(3, PayloadTemplate.BUILT_INS.size)
        assertEquals(
            3,
            PayloadTemplate.BUILT_INS.map { it.id }.toSet().size,
        )
        assertEquals(connectHost, PayloadTemplate.DEFAULT)
    }

    @Test
    fun `CONNECT plus Host is byte-exact`() {
        val text = PayloadGenerator.buildText(connectHost, "ssh.example.com", 443, "sni.example.com")
        assertEquals(
            "CONNECT ssh.example.com:443 HTTP/1.1\r\n" +
                "Host: ssh.example.com\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `CONNECT plus X-Online-Host is byte-exact`() {
        val text = PayloadGenerator.buildText(
            connectXOnline,
            "ssh.example.com",
            443,
            "sni.example.com",
        )
        assertEquals(
            "CONNECT ssh.example.com:443 HTTP/1.1\r\n" +
                "Host: ssh.example.com\r\n" +
                "X-Online-Host: ssh.example.com\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `GET plus Host is byte-exact`() {
        val text = PayloadGenerator.buildText(getHost, "ssh.example.com", 443, "sni.example.com")
        assertEquals(
            "GET http://ssh.example.com/ HTTP/1.1\r\n" +
                "Host: ssh.example.com\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `sni placeholder is substituted`() {
        val template = PayloadTemplate(
            id = "t",
            nameAr = "t",
            method = PayloadMethod.CONNECT,
            target = "{host}:{port}",
            headers = listOf(
                PayloadHeader("Host", "{sni}"),
                PayloadHeader("X-Online-Host", "{sni}"),
            ),
        )
        val text = PayloadGenerator.buildText(template, "ssh.example.com", 443, "zero.example.com")
        assertEquals(
            "CONNECT ssh.example.com:443 HTTP/1.1\r\n" +
                "Host: zero.example.com\r\n" +
                "X-Online-Host: zero.example.com\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `Host header is emitted first even when listed later`() {
        val template = PayloadTemplate(
            id = "t",
            nameAr = "t",
            method = PayloadMethod.GET,
            target = "http://{host}/",
            headers = listOf(
                PayloadHeader("X-Online-Host", "{host}"),
                PayloadHeader("Host", "{host}"),
            ),
        )
        val text = PayloadGenerator.buildText(template, "h.example.com", 80, "")
        assertTrue(text.indexOf("Host:") < text.indexOf("X-Online-Host:"))
    }

    @Test
    fun `custom template text is preserved and CRLF-normalised`() {
        val template = PayloadTemplate.custom(
            id = "c1",
            nameAr = "مخصص",
            text = "CONNECT {host}:{port} HTTP/1.1\nHost: {host}\nX-Test: 1\n",
        )
        val text = PayloadGenerator.buildText(template, "ssh.example.com", 443, "")
        assertEquals(
            "CONNECT ssh.example.com:443 HTTP/1.1\r\n" +
                "Host: ssh.example.com\r\n" +
                "X-Test: 1\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `custom template without trailing blank line gains one`() {
        val template = PayloadTemplate.custom(
            id = "c1",
            nameAr = "مخصص",
            text = "GET http://{host}/ HTTP/1.1\r\nHost: {host}",
        )
        val text = PayloadGenerator.buildText(template, "h.example.com", 80, "")
        assertTrue(text.endsWith("\r\n\r\n"))
    }

    @Test
    fun `CRLF injected in the host cannot smuggle a header`() {
        val evilHost = "ssh.example.com\r\nInjected: evil\r\nX-Second: 2"
        val text = PayloadGenerator.buildText(connectHost, evilHost, 443, "")
        // The injected words survive only flattened inside the host
        // value on ONE line (harmless) — the exact match below proves
        // no extra header line exists in the request.
        assertEquals(4, text.split("\r\n").size)
        assertEquals(
            "CONNECT ssh.example.comInjected: evilX-Second: 2:443 HTTP/1.1\r\n" +
                "Host: ssh.example.comInjected: evilX-Second: 2\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `CRLF injected in the sni value cannot smuggle a header`() {
        val template = PayloadTemplate(
            id = "t",
            nameAr = "t",
            method = PayloadMethod.CONNECT,
            target = "{host}:{port}",
            headers = listOf(PayloadHeader("Host", "{sni}")),
        )
        val text = PayloadGenerator.buildText(
            template,
            "ssh.example.com",
            443,
            "sni.example.com\r\nInjected: evil",
        )
        // Flattened inside the Host value on one line; the exact
        // match proves no extra header line exists.
        assertEquals(4, text.split("\r\n").size)
        assertEquals(
            "CONNECT ssh.example.com:443 HTTP/1.1\r\n" +
                "Host: sni.example.comInjected: evil\r\n" +
                "\r\n",
            text,
        )
    }

    @Test
    fun `CRLF injected in a header value is stripped`() {
        val template = PayloadTemplate(
            id = "t",
            nameAr = "t",
            method = PayloadMethod.CONNECT,
            target = "{host}:{port}",
            headers = listOf(
                PayloadHeader("Host", "{host}"),
                PayloadHeader("X-Test", "a\r\nInjected: evil"),
            ),
        )
        val text = PayloadGenerator.buildText(template, "h.example.com", 443, "")
        // The injected word survives only inside the X-Test value on
        // one line; the exact line count proves no extra header line.
        assertEquals(5, text.split("\r\n").size)
        assertTrue(text.contains("X-Test: aInjected: evil\r\n"))
    }

    @Test
    fun `blank host is rejected`() {
        try {
            PayloadGenerator.buildText(connectHost, "   ", 443, "")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `out-of-range ports are rejected`() {
        for (port in listOf(0, -1, 65536, 100000)) {
            try {
                PayloadGenerator.buildText(connectHost, "h.example.com", port, "")
                fail("expected IllegalArgumentException for port $port")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    @Test
    fun `sni placeholder with a blank sni host is rejected`() {
        val template = PayloadTemplate(
            id = "t",
            nameAr = "t",
            method = PayloadMethod.CONNECT,
            target = "{host}:{port}",
            headers = listOf(PayloadHeader("Host", "{sni}")),
        )
        try {
            PayloadGenerator.buildText(template, "h.example.com", 443, "  ")
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("{sni}"))
        }
    }

    @Test
    fun `empty custom text is rejected`() {
        val template = PayloadTemplate.custom("c1", "مخصص", "  \n  ")
        try {
            PayloadGenerator.buildText(template, "h.example.com", 80, "")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `build returns the same bytes as buildText in UTF-8`() {
        val bytes = PayloadGenerator.build(connectHost, "ssh.example.com", 443, "")
        assertEquals(
            "CONNECT ssh.example.com:443 HTTP/1.1\r\nHost: ssh.example.com\r\n\r\n",
            String(bytes, Charsets.UTF_8),
        )
    }

    @Test
    fun `template JSON round-trips`() {
        val template = PayloadTemplate.custom("c9", "قالب تجريبي", "GET / HTTP/1.1\nHost: x\n")
        val restored = PayloadTemplate.fromJson(template.toJson())
        assertEquals(template, restored)
        val builtIn = PayloadTemplate.fromJson(connectXOnline.toJson())
        assertEquals(connectXOnline, builtIn)
    }
}
