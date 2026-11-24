package dev.aalhdydy550.tunnel.feature.connection.injector.payload

import dev.aalhdydy550.tunnel.feature.connection.injector.InjectorConstants
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Outcome of one host check. */
enum class ScanStatus {
    /** The proxy answered 200/101 for this host. */
    SUCCESS,

    /** TCP failed on every proxy port, or the proxy refused (non-200). */
    FAILED,

    /** The proxy accepted the connection but never answered. */
    TIMEOUT,
}

/** One checked host: what was tried, what answered, and when. */
data class ScannedHost(
    val host: String,
    val proxyPort: Int,
    val status: ScanStatus,
    val latencyMs: Long?,
    val checkedAt: Long,
)

/**
 * Checks which hosts a carrier proxy will CONNECT for.
 *
 * For each host: TCP to the proxy (ports tried in order, default
 * 80 then 8080 — see [InjectorConstants.DEFAULT_PROXY_PORTS]), then
 * a `CONNECT <host>:<targetPort>` request built by
 * [PayloadGenerator] (the built-in CONNECT + Host template), then
 * the status is read with [HttpInjector.readHeaders] (byte-wise, no
 * read-ahead — nothing follows the headers here, the socket is
 * closed after the check).
 *
 * **No host or proxy value is invented here.** The host list comes
 * from the caller — the app's own reference-table SNI hosts plus
 * hosts the user typed — and the proxy address is user-entered too.
 * A SUCCESS here only means "the proxy answered 200 for this host";
 * whether the traffic is actually unmetered is decided only by a
 * field test on the user's SIM with the data counter (see the
 * «نت مجاني» screen).
 *
 * Checks run concurrently ([maxParallel] at a time) on
 * [Dispatchers.IO]; results come back sorted by host so the UI
 * order is stable. Pure `java.net` + coroutines; unit-tested on the
 * JVM against a stub proxy.
 */
class HostScanner(
    private val proxyHost: String,
    private val proxyPorts: List<Int> = InjectorConstants.DEFAULT_PROXY_PORTS,
    private val timeoutMs: Int = InjectorConstants.HOST_SCAN_TIMEOUT_MS,
    private val maxParallel: Int = 4,
) {

    init {
        require(proxyHost.isNotBlank()) { "Proxy host is blank" }
        require(proxyPorts.isNotEmpty()) { "No proxy ports to try" }
        require(proxyPorts.all { it in 1..65535 }) { "Proxy port out of range" }
        require(timeoutMs > 0) { "Timeout must be positive" }
        require(maxParallel >= 1) { "maxParallel must be at least 1" }
    }

    /**
     * Scans [hosts] (blank/duplicated entries dropped, capped at
     * [InjectorConstants.MAX_HOSTS_PER_SCAN]) and returns one
     * [ScannedHost] per host, sorted by host name.
     */
    suspend fun scan(
        hosts: List<String>,
        targetPort: Int = 443,
    ): List<ScannedHost> = withContext(Dispatchers.IO) {
        val distinct = hosts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(InjectorConstants.MAX_HOSTS_PER_SCAN)
        coroutineScope {
            val semaphore = Semaphore(maxParallel)
            distinct.map { host ->
                async {
                    semaphore.withPermit { checkOne(host, targetPort) }
                }
            }.map { it.await() }
        }.sortedBy { it.host.lowercase(Locale.US) }
    }

    /** Checks one host (blocking; called from [scan] on IO). */
    fun checkOne(host: String, targetPort: Int = 443): ScannedHost {
        val startNs = System.nanoTime()
        val checkedAt = System.currentTimeMillis()
        var lastResult: ScannedHost? = null
        for (proxyPort in proxyPorts) {
            val result = tryPort(host, targetPort, proxyPort, startNs, checkedAt)
            // A timeout means the proxy accepted but never answered —
            // retrying another port would only wait again; report it.
            if (result.status == ScanStatus.TIMEOUT) return result
            if (result.status == ScanStatus.SUCCESS) return result
            // FAILED on this port: keep the measured result and try
            // the next one.
            lastResult = result
        }
        // Every port failed: report the last attempt (with its
        // measured latency), not a fresh latency-less row.
        return lastResult
            ?: ScannedHost(host, proxyPorts.last(), ScanStatus.FAILED, null, checkedAt)
    }

    private fun tryPort(
        host: String,
        targetPort: Int,
        proxyPort: Int,
        startNs: Long,
        checkedAt: Long,
    ): ScannedHost {
        val socket = Socket()
        return try {
            socket.soTimeout = timeoutMs
            socket.connect(InetSocketAddress(proxyHost, proxyPort), timeoutMs)
            val payload = PayloadGenerator.build(
                PayloadTemplate.DEFAULT,
                host,
                targetPort,
                host,
            )
            socket.getOutputStream().apply {
                write(payload)
                flush()
            }
            val headers = HttpInjector.readHeaders(socket.getInputStream())
            val code = HttpInjector.parseStatusCode(headers)
            val latencyMs = (System.nanoTime() - startNs) / 1_000_000
            if (code == 200 || code == 101) {
                ScannedHost(host, proxyPort, ScanStatus.SUCCESS, latencyMs, checkedAt)
            } else {
                ScannedHost(host, proxyPort, ScanStatus.FAILED, latencyMs, checkedAt)
            }
        } catch (e: SocketTimeoutException) {
            ScannedHost(host, proxyPort, ScanStatus.TIMEOUT, null, checkedAt)
        } catch (_: Exception) {
            ScannedHost(host, proxyPort, ScanStatus.FAILED, null, checkedAt)
        } finally {
            runCatching { socket.close() }
        }
    }
}
