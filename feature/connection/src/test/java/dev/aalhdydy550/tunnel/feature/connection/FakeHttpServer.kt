package dev.aalhdydy550.tunnel.feature.connection

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Minimal HTTP/1.1 stub server for unit tests, built on java.net only.
 *
 * Why not `com.sun.net.httpserver.HttpServer`: that class lives in the
 * `jdk.httpserver` JDK module, which the Android Gradle Plugin does not
 * put on the unit-test compile classpath — CI failed with
 * "Unresolved reference 'sun'" in :core:data:compileDebugUnitTestKotlin
 * (run 58), while the same tests passed on a plain JVM. `java.net` is in
 * `java.base`, so it is always available.
 *
 * Copy of the helper in :core:data test sources (same name, this module's
 * package): test source sets are not shared across modules.
 *
 * Binds IPv6 loopback (::1) — on this dev VM the sandbox fake-accepts
 * Java TCP connects on IPv4 loopback (127.0.0.1) and no data flows there,
 * while ::1 flows for real; ::1 works in CI too (same pattern the
 * HttpServer-based tests used).
 *
 * One route per path; a request for an unknown path gets 404. Each
 * connection is handled on its own daemon thread and closed after one
 * response (`Connection: close`), so concurrent probes work and nothing
 * blocks JVM exit.
 */
class FakeHttpServer {

    private data class Route(val status: Int, val body: ByteArray, val delayMs: Long)

    private val routes = linkedMapOf<String, Route>()
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("::1"))

    /** The ephemeral port the server bound to. */
    val port: Int get() = serverSocket.localPort

    private var acceptThread: Thread? = null

    fun route(path: String, status: Int, body: ByteArray = ByteArray(0), delayMs: Long = 0) {
        routes[path] = Route(status, body, delayMs)
    }

    fun route(path: String, status: Int, body: String, delayMs: Long = 0) {
        route(path, status, body.toByteArray(Charsets.UTF_8), delayMs)
    }

    fun start() {
        acceptThread = thread(name = "fake-http-server", isDaemon = true) {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    return@thread
                }
                thread(name = "fake-http-conn", isDaemon = true) { handle(socket) }
            }
        }
    }

    fun stop() {
        try {
            serverSocket.close()
        } catch (_: Exception) {
            // Already closed.
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 10_000
            val input = s.getInputStream().bufferedReader(Charsets.UTF_8)
            val requestLine = input.readLine() ?: return
            val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
            // Consume the request headers up to the blank line.
            var line: String?
            do {
                line = input.readLine()
            } while (line != null && line.isNotEmpty())
            val route = routes[path] ?: Route(404, ByteArray(0), 0)
            if (route.delayMs > 0) Thread.sleep(route.delayMs)
            val reason = when (route.status) {
                200 -> "OK"
                204 -> "No Content"
                404 -> "Not Found"
                500 -> "Internal Server Error"
                else -> "OK"
            }
            val out = s.getOutputStream()
            val headers = "HTTP/1.1 ${route.status} $reason\r\n" +
                "Content-Length: ${route.body.size}\r\n" +
                "Connection: close\r\n\r\n"
            out.write(headers.toByteArray(Charsets.UTF_8))
            out.write(route.body)
            out.flush()
        }
    }
}
