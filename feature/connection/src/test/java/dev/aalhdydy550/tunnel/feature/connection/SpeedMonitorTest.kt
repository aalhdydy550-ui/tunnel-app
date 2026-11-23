package dev.aalhdydy550.tunnel.feature.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [SpeedMonitor] with a fake sampler: the delta math
 * is deterministic under a test scheduler.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedMonitorTest {

    @Test
    fun `speed is computed from sampler deltas`() = runTest {
        var calls = 0
        val monitor = SpeedMonitor(
            scope = this,
            sample = {
                calls++
                (calls * 1_000L) to (calls * 500L)
            },
            intervalMs = 100,
        )
        monitor.start()
        advanceTimeBy(350)
        val speed = monitor.speed.value
        // +1000 rx bytes per 100ms = 10,000 B/s; +500 tx = 5,000 B/s.
        assertEquals(10_000L, speed.rxBytesPerSec)
        assertEquals(5_000L, speed.txBytesPerSec)
        monitor.stop()
    }

    @Test
    fun `stop resets the speed to zero`() = runTest {
        var calls = 0
        val monitor = SpeedMonitor(
            scope = this,
            sample = {
                calls++
                (calls * 1_000L) to (calls * 500L)
            },
            intervalMs = 100,
        )
        monitor.start()
        advanceTimeBy(250)
        monitor.stop()
        assertEquals(0L, monitor.speed.value.rxBytesPerSec)
        assertEquals(0L, monitor.speed.value.txBytesPerSec)
    }

    @Test
    fun `a second start while running is ignored`() = runTest {
        var calls = 0
        val monitor = SpeedMonitor(
            scope = this,
            sample = {
                calls++
                (calls * 1_000L) to (calls * 500L)
            },
            intervalMs = 100,
        )
        monitor.start()
        monitor.start()
        advanceTimeBy(250)
        // One loop only: 3 samples (initial + 2 intervals).
        assertEquals(3, calls)
        monitor.stop()
    }
}
