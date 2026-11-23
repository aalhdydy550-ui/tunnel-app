package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.net.ReconnectPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/** Unit tests for the reconnect backoff table. */
class ReconnectPolicyTest {

    @Test
    fun `backoff grows then caps at 30 seconds`() {
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(1))
        assertEquals(5_000L, ReconnectPolicy.delayForAttempt(2))
        assertEquals(10_000L, ReconnectPolicy.delayForAttempt(3))
        assertEquals(20_000L, ReconnectPolicy.delayForAttempt(4))
        assertEquals(30_000L, ReconnectPolicy.delayForAttempt(5))
        assertEquals(30_000L, ReconnectPolicy.delayForAttempt(6))
        assertEquals(30_000L, ReconnectPolicy.delayForAttempt(100))
    }

    @Test
    fun `attempt zero or negative clamps to the first delay`() {
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(0))
        assertEquals(2_000L, ReconnectPolicy.delayForAttempt(-3))
    }
}
