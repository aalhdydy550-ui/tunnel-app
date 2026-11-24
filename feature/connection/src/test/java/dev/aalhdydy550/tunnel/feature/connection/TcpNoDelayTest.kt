package dev.aalhdydy550.tunnel.feature.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket

/**
 * JVM tests for [TcpNoDelay] (§4-#18): the default (off) leaves the
 * socket exactly as the OS created it, and enabling sets the option.
 */
class TcpNoDelayTest {

    @Test
    fun `default is off`() {
        assertEquals(false, TcpNoDelay.DEFAULT_ENABLED)
    }

    @Test
    fun `applying with enabled false leaves the socket untouched`() {
        Socket().use { socket ->
            assertFalse(socket.tcpNoDelay)
            TcpNoDelay.apply(socket, false)
            assertFalse(socket.tcpNoDelay)
        }
    }

    @Test
    fun `applying with enabled true sets TCP_NODELAY`() {
        Socket().use { socket ->
            TcpNoDelay.apply(socket, true)
            assertTrue(socket.tcpNoDelay)
        }
    }

    @Test
    fun `toggling back off clears the option`() {
        Socket().use { socket ->
            TcpNoDelay.apply(socket, true)
            assertTrue(socket.tcpNoDelay)
            TcpNoDelay.apply(socket, false)
            assertFalse(socket.tcpNoDelay)
        }
    }
}
