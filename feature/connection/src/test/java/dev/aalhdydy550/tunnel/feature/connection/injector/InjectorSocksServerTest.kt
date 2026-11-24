package dev.aalhdydy550.tunnel.feature.connection.injector

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests [InjectorSocksServer] against a fake channel factory backed
 * by piped streams — no real SSH session and no real host are ever
 * contacted.
 */
class InjectorSocksServerTest {

    /** Fake forwarded channel: piped streams stand in for the remote side. */
    private class FakeChannel : InjectorSocksServer.Channel {
        // Bytes the "remote server" sends back to the SOCKS client.
        val remoteToClient = PipedOutputStream()
        override val input: InputStream = PipedInputStream(remoteToClient)

        // Bytes the SOCKS client sends to the "remote server".
        val clientToRemote = PipedInputStream()
        override val output: OutputStream = PipedOutputStream(clientToRemote)

        override fun close() {
            runCatching { remoteToClient.close() }
            runCatching { input.close() }
            runCatching { output.close() }
            runCatching { clientToRemote.close() }
        }
    }

    private lateinit var server: InjectorSocksServer
    private val opened = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
    private val channels = Collections.synchronizedList(mutableListOf<FakeChannel>())

    @Volatile
    private var failOpen = false

    @Before
    fun setUp() {
        opened.clear()
        channels.clear()
        failOpen = false
        server = InjectorSocksServer(
            bindAddress = "127.0.0.1",
            port = 0,
            channelFactory = InjectorSocksServer.ChannelFactory { host, port ->
                if (failOpen) throw IOException("fake open failure")
                opened.add(host to port)
                FakeChannel().also { channels.add(it) }
            },
        )
        server.start()
        assertTrue(server.isRunning)
        assertTrue(server.boundPort > 0)
    }

    @After
    fun tearDown() {
        server.stop()
        channels.forEach { runCatching { it.close() } }
    }

    @Test
    fun `ipv4 CONNECT relays data both ways`() {
        newClient().use { socket ->
            val reply = socksConnect(socket, cmd = 1, atyp = 1, addressField = byteArrayOf(127, 0, 0, 1), port = 443)
            assertArrayEquals(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0), reply)
            assertEquals(listOf("127.0.0.1" to 443), opened)
            val channel = channels.single()

            socket.getOutputStream().write("ping".toByteArray(Charsets.US_ASCII))
            socket.getOutputStream().flush()
            val received = ByteArray(4)
            assertTrue(readFully(channel.clientToRemote, received))
            assertArrayEquals("ping".toByteArray(Charsets.US_ASCII), received)

            channel.remoteToClient.write("pong".toByteArray(Charsets.US_ASCII))
            channel.remoteToClient.flush()
            val echoed = ByteArray(4)
            assertTrue(readFully(socket.getInputStream(), echoed))
            assertArrayEquals("pong".toByteArray(Charsets.US_ASCII), echoed)
        }
    }

    @Test
    fun `domain CONNECT passes hostname and port to the channel factory`() {
        newClient().use { socket ->
            val name = "example.test".toByteArray(Charsets.US_ASCII)
            val reply = socksConnect(socket, cmd = 1, atyp = 3, addressField = byteArrayOf(name.size.toByte()) + name, port = 8443)
            assertEquals(0, reply[1].toInt())
            assertEquals(listOf("example.test" to 8443), opened)
        }
    }

    @Test
    fun `greeting without no-auth method is refused`() {
        newClient().use { socket ->
            val output = socket.getOutputStream()
            val input = socket.getInputStream()
            output.write(byteArrayOf(5, 1, 2))
            output.flush()
            val reply = ByteArray(2)
            assertTrue(readFully(input, reply))
            assertArrayEquals(byteArrayOf(5, 0xFF.toByte()), reply)
            assertEquals(-1, input.read())
        }
    }

    @Test
    fun `non-CONNECT command is rejected with reply 7`() {
        newClient().use { socket ->
            val reply = socksConnect(socket, cmd = 2, atyp = 1, addressField = byteArrayOf(127, 0, 0, 1), port = 443)
            assertEquals(7, reply[1].toInt())
            assertTrue(opened.isEmpty())
        }
    }

    @Test
    fun `channel open failure replies connection refused`() {
        failOpen = true
        newClient().use { socket ->
            val reply = socksConnect(socket, cmd = 1, atyp = 1, addressField = byteArrayOf(127, 0, 0, 1), port = 443)
            assertEquals(5, reply[1].toInt())
        }
    }

    @Test
    fun `start fails when the port is already bound`() {
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { taken ->
            val clash = InjectorSocksServer(
                bindAddress = "127.0.0.1",
                port = taken.localPort,
                channelFactory = InjectorSocksServer.ChannelFactory { _, _ -> error("unused") },
            )
            assertThrows(IOException::class.java) { clash.start() }
        }
    }

    @Test
    fun `stop closes the listener`() {
        val port = server.boundPort
        server.stop()
        assertThrows(IOException::class.java) {
            Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 1000) }
        }
    }

    private fun newClient(): Socket {
        return Socket().apply {
            connect(InetSocketAddress("127.0.0.1", server.boundPort), 5000)
            soTimeout = 5000
        }
    }

    /**
     * Performs the SOCKS5 greeting (no-auth) and one request, and
     * returns the 10-byte reply.
     */
    private fun socksConnect(socket: Socket, cmd: Int, atyp: Int, addressField: ByteArray, port: Int): ByteArray {
        val output = socket.getOutputStream()
        val input = socket.getInputStream()
        output.write(byteArrayOf(5, 1, 0))
        output.flush()
        val methodReply = ByteArray(2)
        assertTrue(readFully(input, methodReply))
        assertArrayEquals(byteArrayOf(5, 0), methodReply)
        val request = byteArrayOf(5, cmd.toByte(), 0, atyp.toByte()) +
            addressField +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        output.write(request)
        output.flush()
        val reply = ByteArray(10)
        assertTrue(readFully(input, reply))
        return reply
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Boolean {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return false
            offset += read
        }
        return true
    }
}
