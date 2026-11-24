package dev.aalhdydy550.tunnel.feature.connection

import android.net.TrafficStats
import android.os.Process
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live throughput display: samples the app's own UID traffic counters
 * every [intervalMs] and reports bytes/second.
 *
 * Why the app UID: the tunnel's encrypted traffic leaves through the
 * app's own sockets (the sing-box outbounds), so the UID counters track
 * the real tunnel throughput. The numbers are the encrypted stream
 * (slightly above the payload rate) — honest as "current connection
 * speed", not as a benchmark.
 *
 * The sampler is injectable so the delta math is unit-testable without
 * Android. When [TrafficStats] reports UNSUPPORTED (-1), the speed
 * stays 0 rather than showing nonsense.
 *
 * [cumulative] exposes the latest raw UID counters behind the rate:
 * the usage-statistics repository ([dev.aalhdydy550.tunnel.core.data
 * .stats.UsageStatsRepository]) turns them into per-session totals.
 * It costs nothing extra — the counters are already sampled here.
 */
class SpeedMonitor(
    private val scope: CoroutineScope,
    private val sample: () -> Pair<Long, Long> = ::defaultSample,
    private val intervalMs: Long = 2_000L,
) {
    data class Speed(val rxBytesPerSec: Long, val txBytesPerSec: Long)

    private val _speed = MutableStateFlow(Speed(0, 0))
    val speed: StateFlow<Speed> = _speed.asStateFlow()

    /**
     * Latest raw cumulative UID counters (rx, tx) from [sample].
     * Unlike [speed] this never resets to zero while running, so a
     * consumer can difference successive values into session totals.
     */
    private val _cumulative = MutableStateFlow(0L to 0L)
    val cumulative: StateFlow<Pair<Long, Long>> = _cumulative.asStateFlow()

    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            var previous = sample()
            _cumulative.value = previous
            while (isActive) {
                delay(intervalMs)
                val current = sample()
                val rx = ((current.first - previous.first).coerceAtLeast(0) * 1_000) / intervalMs
                val tx = ((current.second - previous.second).coerceAtLeast(0) * 1_000) / intervalMs
                _speed.value = Speed(rx, tx)
                _cumulative.value = current
                previous = current
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        _speed.value = Speed(0, 0)
        _cumulative.value = 0L to 0L
    }

    companion object {
        fun defaultSample(): Pair<Long, Long> {
            val rx = TrafficStats.getUidRxBytes(Process.myUid())
            val tx = TrafficStats.getUidTxBytes(Process.myUid())
            return (if (rx < 0) 0 else rx) to (if (tx < 0) 0 else tx)
        }
    }
}

/**
 * Shared holder for the live throughput, like [TunnelStateHolder] for
 * the state: the service writes it (from its [SpeedMonitor]), the UI
 * collects it. Same process in v0.1.
 */
object TunnelSpeedHolder {
    private val _speed = MutableStateFlow(SpeedMonitor.Speed(0, 0))
    val speed: StateFlow<SpeedMonitor.Speed> = _speed.asStateFlow()

    fun update(speed: SpeedMonitor.Speed) {
        _speed.value = speed
    }
}
