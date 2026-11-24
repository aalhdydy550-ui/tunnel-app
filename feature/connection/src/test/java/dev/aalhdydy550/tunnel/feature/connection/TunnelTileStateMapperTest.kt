package dev.aalhdydy550.tunnel.feature.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [TunnelTileStateMapper] — the pure TunnelState →
 * tile-state mapping. No Android classes involved (the `Tile`
 * constants live in `TunnelTileService`), so these run on the JVM.
 */
class TunnelTileStateMapperTest {

    @Test
    fun `started maps to connected`() {
        assertEquals(
            TileUiState.CONNECTED,
            TunnelTileStateMapper.map(TunnelState.Started("Home")),
        )
    }

    @Test
    fun `starting stopping and reconnecting map to connecting`() {
        assertEquals(
            TileUiState.CONNECTING,
            TunnelTileStateMapper.map(TunnelState.Starting("Home")),
        )
        assertEquals(
            TileUiState.CONNECTING,
            TunnelTileStateMapper.map(TunnelState.Stopping("Home")),
        )
        assertEquals(
            TileUiState.CONNECTING,
            TunnelTileStateMapper.map(
                TunnelState.Reconnecting("Home", 2, "network changed"),
            ),
        )
    }

    @Test
    fun `stopped and error map to disconnected`() {
        assertEquals(
            TileUiState.DISCONNECTED,
            TunnelTileStateMapper.map(TunnelState.Stopped),
        )
        assertEquals(
            TileUiState.DISCONNECTED,
            TunnelTileStateMapper.map(TunnelState.Error("VPN permission revoked")),
        )
    }

    @Test
    fun `blocked maps to connecting so the tile stays tappable`() {
        // Kill switch blocking (item 2): the tile stays ACTIVE and
        // tappable, and a tap stops the tunnel (the escape hatch) —
        // it must never map to DISCONNECTED (a tap would try to
        // start a new tunnel while the TUN is held open).
        assertEquals(
            TileUiState.CONNECTING,
            TunnelTileStateMapper.map(TunnelState.Blocked("Home")),
        )
        assertTrue(TunnelTileStateMapper.isRunning(TunnelState.Blocked("Home")))
        assertFalse(TunnelTileStateMapper.shouldStart(TunnelState.Blocked("Home")))
    }

    @Test
    fun `isRunning is true for up and transitional states only`() {
        assertTrue(TunnelTileStateMapper.isRunning(TunnelState.Started("Home")))
        assertTrue(TunnelTileStateMapper.isRunning(TunnelState.Starting("Home")))
        assertTrue(
            TunnelTileStateMapper.isRunning(
                TunnelState.Reconnecting("Home", 1, "timeout"),
            ),
        )
        assertFalse(TunnelTileStateMapper.isRunning(TunnelState.Stopped))
        assertFalse(TunnelTileStateMapper.isRunning(TunnelState.Error("x")))
    }

    @Test
    fun `shouldStart is true only when fully down`() {
        assertTrue(TunnelTileStateMapper.shouldStart(TunnelState.Stopped))
        assertTrue(TunnelTileStateMapper.shouldStart(TunnelState.Error("x")))
        assertFalse(TunnelTileStateMapper.shouldStart(TunnelState.Started("Home")))
        assertFalse(TunnelTileStateMapper.shouldStart(TunnelState.Starting("Home")))
        assertFalse(
            TunnelTileStateMapper.shouldStart(
                TunnelState.Reconnecting("Home", 1, "timeout"),
            ),
        )
    }
}
