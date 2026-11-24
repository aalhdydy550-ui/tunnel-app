package dev.aalhdydy550.tunnel.feature.connection.injector

import dev.aalhdydy550.tunnel.feature.connection.FakeHttpServer
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.HttpInjectSocketFactory
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.HttpInjector
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadGenerator
import dev.aalhdydy550.tunnel.feature.connection.injector.payload.PayloadTemplate
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [HttpInjector] + [HttpInjectSocketFactory] against the local stub
 * server — no external network of any kind.
 *
 * Note: the servers bind IPv6 loopback (::1) — on this dev VM the
 * sandbox fake-accepts Java TCP connects on IPv4 loopback
 * (127.0.0.1) and no data flows there, while ::1 flows for real;
 * ::1 works in CI too (same pattern as [FakeHttpServer]'s own
 * documentation).
 */
class HttpInjectorTest {

    private fun connectTo(port: Int): Socket =
        Socket().apply { connect(InetSocketAddress("::1", port), 5_000) }

    private fun payloadFor(host: String = "example.com", port: Int = 443): ByteArray =
        PayloadGenerator.build(PayloadTemplate.DEFAULT, host, port, host)

    // ------------------------------------------------------------ inject()

    @Test
    fun `200 means success and the socket stays open`() {
        val server = FakeHttpServer()
        server.route("example.com:443", 200)
        server.start()
        try {
            val socket = connectTo(server.port)
            try {
                val result = HttpInjector().inject(socket, payloadFor())
                assertTrue(result.isSuccess)
                assertFalse(socket.isClosed)
            } finally {
                socket.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `403 means failure with the status in the message`() {
        val server = FakeHttpServer()
        server.route("example.com:443", 403)
        server.start()
        try {
            val socket = connectTo(server.port)
            val result = HttpInjector().inject(socket, payloadFor())
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message?.contains("403") == true)
            assertTrue(socket.isClosed)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `407 means failure`() {
        val server = FakeHttpServer()
        server.route("example.com:443", 407)
        server.start()
        try {
            val socket = connectTo(server.port)
            val result = HttpInjector().inject(socket, payloadFor())
            assertTrue(result.isFailure)
            assertTrue(socket.isClosed)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `early close means failure`() {
        val proxy = RawStubProxy { socket -> socket.close() }
        proxy.start()
        try {
            val socket = connectTo(proxy.port)
            val result = HttpInjector().inject(socket, payloadFor())
            assertTrue(result.isFailure)
            assertTrue(socket.isClosed)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `no answer within the timeout means failure`() {
        val server = FakeHttpServer()
        server.route("example.com:443", 200, delayMs = 2_000)
        server.start()
        try {
            val socket = connectTo(server.port)
            val result = HttpInjector(timeoutMs = 300).inject(socket, payloadFor())
            assertTrue(result.isFailure)
            assertTrue(socket.isClosed)
        } finally {
            server.stop()
        }
    }

    @Test
    fun `malformed status line means failure`() {
        val proxy = RawStubProxy { socket ->
            socket.getOutputStream().write("GARBAGE\r\n\r\n".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            Thread.sleep(500)
        }
        proxy.start()
        try {
            val socket = connectTo(proxy.port)
            val result = HttpInjector().inject(socket, payloadFor())
            assertTrue(result.isFailure)
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `empty payload means failure without opening anything`() {
        val server = FakeHttpServer()
        server.start()
        try {
            val socket = connectTo(server.port)
            val result = HttpInjector().inject(socket, ByteArray(0))
            assertTrue(result.isFailure)
            socket.close()
        } finally {
            server.stop()
        }
    }

    // -------------------------------------------------------- readHeaders()

    @Test
    fun `readHeaders stops at the blank line and reads nothing past it`() {
        val bytes = "HTTP/1.1 200 OK\r\nX-Test: 1\r\n\r\nEXTRA".toByteArray(Charsets.US_ASCII)
        val input = ByteArrayInputStream(bytes)
        val headers = HttpInjector.readHeaders(input)
        assertEquals("HTTP/1.1 200 OK\r\nX-Test: 1\r\n\r\n", headers)
        // The bytes after the headers are still unread — a buffered
        // reader would have swallowed them (and with them, the SSH
        // server banner that follows a real 200).
        assertEquals(5, input.available())
    }

    @Test
    fun `readHeaders accepts bare LF endings`() {
        val bytes = "HTTP/1.1 200 OK\nX: y\n\n".toByteArray(Charsets.US_ASCII)
        assertEquals(
            "HTTP/1.1 200 OK\nX: y\n\n",
            HttpInjector.readHeaders(ByteArrayInputStream(bytes)),
        )
    }

    @Test
    fun `readHeaders fails on EOF before the blank line`() {
        val bytes = "HTTP/1.1 200 OK\r\nX: y\r\n".toByteArray(Charsets.US_ASCII)
        try {
            HttpInjector.readHeaders(ByteArrayInputStream(bytes))
            fail("expected IOException")
        } catch (_: java.io.IOException) {
            // Expected.
        }
    }

    // ---------------------------------------------------- parseStatusCode()

    @Test
    fun `parseStatusCode reads 200 403 and 101`() {
        assertEquals(200, HttpInjector.parseStatusCode("HTTP/1.1 200 OK\r\n\r\n"))
        assertEquals(403, HttpInjector.parseStatusCode("HTTP/1.0 403 Forbidden\r\n\r\n"))
        assertEquals(101, HttpInjector.parseStatusCode("HTTP/1.1 101 Switching Protocols\r\n\r\n"))
    }

    @Test
    fun `parseStatusCode returns null on garbage`() {
        assertNull(HttpInjector.parseStatusCode("GARBAGE\r\n\r\n"))
        assertNull(HttpInjector.parseStatusCode(""))
        assertNull(HttpInjector.parseStatusCode("HTTP/1.1 abc\r\n\r\n"))
    }

    // -------------------------------------------------- parseProxyAddress()

    @Test
    fun `parseProxyAddress handles host port and brackets`() {
        assertEquals("proxy.example.com" to 80, HttpInjector.parseProxyAddress("proxy.example.com"))
        assertEquals("proxy.example.com" to 8080, HttpInjector.parseProxyAddress("proxy.example.com:8080"))
        assertEquals("::1" to 8080, HttpInjector.parseProxyAddress("[::1]:8080"))
        assertEquals("::1" to 80, HttpInjector.parseProxyAddress("[::1]"))
        assertNull(HttpInjector.parseProxyAddress(""))
        assertNull(HttpInjector.parseProxyAddress("   "))
        assertNull(HttpInjector.parseProxyAddress("proxy.example.com:abc"))
        assertNull(HttpInjector.parseProxyAddress("proxy.example.com:0"))
        assertNull(HttpInjector.parseProxyAddress("proxy.example.com:70000"))
        assertNull(HttpInjector.parseProxyAddress("::1"))
        assertNull(HttpInjector.parseProxyAddress("has space.com"))
        assertNull(HttpInjector.parseProxyAddress(":8080"))
    }

    // ------------------------------------------------------------ factory

    @Test
    fun `factory returns an open socket on 200`() {
        val server = FakeHttpServer()
        server.route("example.com:443", 200)
        server.start()
        try {
            val factory = HttpInjectSocketFactory("::1", server.port, payloadFor())
            val socket = factory.createSocket("example.com", 443)
            try {
                assertFalse(socket.isClosed)
                // The factory's streams are the socket's streams.
                assertEquals(socket.getInputStream(), factory.getInputStream(socket))
                assertEquals(socket.getOutputStream(), factory.getOutputStream(socket))
            } finally {
                socket.close()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `factory throws on 403`() {
        val server = FakeHttpServer()
        server.route("example.com:443", 403)
        server.start()
        try {
            val factory = HttpInjectSocketFactory("::1", server.port, payloadFor())
            try {
                factory.createSocket("example.com", 443)
                fail("expected IOException")
            } catch (_: java.io.IOException) {
                // Expected.
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `factory rejects bad parameters`() {
        for (bad in listOf(
            { HttpInjectSocketFactory("", 80, payloadFor()) },
            { HttpInjectSocketFactory("::1", 0, payloadFor()) },
            { HttpInjectSocketFactory("::1", 80, ByteArray(0)) },
            { HttpInjectSocketFactory("::1", 80, payloadFor(), timeoutMs = 0) },
        )) {
            try {
                bad()
                fail("expected IllegalArgumentException")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
    }

    /**
     * Minimal raw TCP stub: accepts connections on ::1 and runs
     * [action] for each one on a daemon thread. Used where
     * [FakeHttpServer] cannot express the case (early close,
     * malformed response).
     */
    private class RawStubProxy(private val action: (Socket) -> Unit) {
        private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("::1"))
        val port: Int get() = serverSocket.localPort

        fun start() {
            thread(name = "raw-stub-proxy", isDaemon = true) {
                while (!serverSocket.isClosed) {
                    val socket = try {
                        serverSocket.accept()
                    } catch (_: Exception) {
                        return@thread
                    }
                    thread(name = "raw-stub-conn", isDaemon = true) {
                        try {
                            action(socket)
                        } catch (_: Exception) {
                            // The test reads the outcome from its side.
                        } finally {
                            runCatching { socket.close() }
                        }
                    }
                }
            }
        }

        fun stop() {
            runCatching { serverSocket.close() }
        }
    }
}
