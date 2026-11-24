package dev.aalhdydy550.tunnel.feature.connection.injector

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A minimal SOCKS5 server (RFC 1928, CONNECT only, no authentication)
 * that forwards each accepted connection through a caller-supplied
 * channel — in production a JSch `direct-tcpip` channel on the live
 * SSH session (see [InjectorSshClient]).
 *
 * Why this class exists instead of one JSch call: mwiede's JSch fork
 * (the `com.github.mwiede:jsch` artifact this app uses, 2.28.6) has
 * **no `Session.setPortForwardingD`** — verified against the 2.28.6
 * jar and the upstream source, which only documents local, remote
 * and stream forwarding. The original `com.jcraft:jsch` had the
 * method, but it is unmaintained since 2018, so the dynamic forward
 * is implemented here on top of `direct-tcpip` channels instead of
 * downgrading the SSH library. The observable behaviour is the same:
 * sing-box talks SOCKS5 to 127.0.0.1:[InjectorConstants.INJECTOR_SOCKS_PORT].
 *
 * The server binds loopback only (never 0.0.0.0) and every thread it
 * spawns is a daemon thread, so a forgotten server can never keep
 * the process — or a unit-test JVM — alive. [stop] is idempotent and
 * never throws.
 *
 * No connection is ever made from a unit test to a real host: tests
 * supply a fake [ChannelFactory] backed by piped streams.
 */
class InjectorSocksServer(
    private val bindAddress: String,
    private val port: Int,
    private val channelFactory: ChannelFactory,
) {

    /** Opens one forwarded channel to [host]:[port]. May throw. */
    fun interface ChannelFactory {
        fun open(host: String, port: Int): Channel
    }

    /** One forwarded byte stream. Implementations must make [close] idempotent. */
    interface Channel {
        val input: InputStream
        val output: OutputStream
        fun close()
    }

    @Volatile
    private var serverSocket: ServerSocket? = null

    private var acceptThread: Thread? = null

    private val liveSockets = Collections.synchronizedSet(mutableSetOf<Socket>())

    /** True while the listener socket is open. */
    val isRunning: Boolean
        get() = serverSocket?.isClosed == false

    /** The port the listener is actually bound to (-1 when stopped). */
    val boundPort: Int
        get() = serverSocket?.localPort ?: -1

    /**
     * Binds the listener and starts accepting. Throws [IOException]
     * when the port cannot be bound (e.g. already in use) — the
     * caller must treat that as a failed start and not proceed.
     */
    @Throws(IOException::class)
    fun start() {
        check(serverSocket == null) { "SOCKS server already started" }
        val socket = ServerSocket(port, 50, InetAddress.getByName(bindAddress))
        serverSocket = socket
        acceptThread = thread(start = true, isDaemon = true, name = "injector-socks-accept") {
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    liveSockets.add(client)
                    thread(start = true, isDaemon = true, name = "injector-socks-conn") {
                        try {
                            handle(client)
                        } catch (_: Exception) {
                            // Any protocol or I/O error just drops this
                            // connection; the server keeps accepting.
                        } finally {
                            liveSockets.remove(client)
                            runCatching { client.close() }
                        }
                    }
                } catch (_: IOException) {
                    // Listener closed by stop(); leave the loop.
                    break
                }
            }
        }
    }

    /** Closes the listener and every live connection. Never throws. */
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        liveSockets.toList().forEach { runCatching { it.close() } }
        liveSockets.clear()
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val output = client.getOutputStream()

        // --- Greeting: VER, NMETHODS, METHODS... ---
        if (input.read() != SOCKS_VERSION) return
        val methodCount = input.read()
        if (methodCount < 0) return
        val methods = ByteArray(methodCount)
        if (!readFully(input, methods)) return
        if (!methods.contains(METHOD_NO_AUTH.toByte())) {
            output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_ACCEPTABLE.toByte()))
            output.flush()
            return
        }
        output.write(byteArrayOf(SOCKS_VERSION.toByte(), METHOD_NO_AUTH.toByte()))
        output.flush()

        // --- Request: VER, CMD, RSV, ATYP, DST.ADDR, DST.PORT ---
        val header = ByteArray(4)
        if (!readFully(input, header)) return
        if (header[0].toInt() != SOCKS_VERSION || header[1].toInt() != CMD_CONNECT) {
            writeReply(output, REP_COMMAND_NOT_SUPPORTED)
            return
        }
        val host: String = when (header[3].toInt()) {
            ATYP_IPV4 -> {
                val address = ByteArray(4)
                if (!readFully(input, address)) return
                InetAddress.getByAddress(address).hostAddress
            }
            ATYP_DOMAIN -> {
                val length = input.read()
                if (length < 0) return
                val name = ByteArray(length)
                if (!readFully(input, name)) return
                String(name, Charsets.US_ASCII)
            }
            ATYP_IPV6 -> {
                val address = ByteArray(16)
                if (!readFully(input, address)) return
                InetAddress.getByAddress(address).hostAddress
            }
            else -> {
                writeReply(output, REP_ADDRESS_TYPE_NOT_SUPPORTED)
                return
            }
        }
        val portBytes = ByteArray(2)
        if (!readFully(input, portBytes)) return
        val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or
            (portBytes[1].toInt() and 0xFF)

        val channel = try {
            channelFactory.open(host, targetPort)
        } catch (_: Exception) {
            writeReply(output, REP_CONNECTION_REFUSED)
            return
        }
        try {
            writeReply(output, REP_SUCCEEDED)
            relay(client, channel)
        } finally {
            runCatching { channel.close() }
        }
    }

    /** Pumps bytes in both directions until either side ends. */
    private fun relay(client: Socket, channel: Channel) {
        val clientIn = client.getInputStream()
        val clientOut = client.getOutputStream()
        val remoteToClient = thread(start = true, isDaemon = true, name = "injector-socks-pump") {
            copyQuietly(channel.input, clientOut)
            // Remote side ended: half-close so the client sees EOF.
            runCatching { client.shutdownOutput() }
        }
        try {
            copyQuietly(clientIn, channel.output)
        } finally {
            runCatching { channel.close() }
            runCatching { client.close() }
            remoteToClient.join(1000)
        }
    }

    private fun copyQuietly(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                output.write(buffer, 0, read)
                output.flush()
            }
        } catch (_: IOException) {
            // Peer went away; relay()'s finally closes everything.
        }
    }

    private fun writeReply(output: OutputStream, reply: Int) {
        output.write(
            byteArrayOf(
                SOCKS_VERSION.toByte(),
                reply.toByte(),
                0,
                ATYP_IPV4.toByte(),
                0, 0, 0, 0,
                0, 0,
            ),
        )
        output.flush()
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

    private companion object {
        const val SOCKS_VERSION = 5
        const val METHOD_NO_AUTH = 0
        const val METHOD_NO_ACCEPTABLE = 0xFF
        const val CMD_CONNECT = 1
        const val ATYP_IPV4 = 1
        const val ATYP_DOMAIN = 3
        const val ATYP_IPV6 = 4
        const val REP_SUCCEEDED = 0
        const val REP_CONNECTION_REFUSED = 5
        const val REP_ADDRESS_TYPE_NOT_SUPPORTED = 8
        const val REP_COMMAND_NOT_SUPPORTED = 7
    }
}
