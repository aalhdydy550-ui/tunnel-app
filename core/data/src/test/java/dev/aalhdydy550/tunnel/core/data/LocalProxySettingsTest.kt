package dev.aalhdydy550.tunnel.core.data

import dev.aalhdydy550.tunnel.core.data.net.DEFAULT_LOCAL_PROXY_PORT
import dev.aalhdydy550.tunnel.core.data.net.LocalProxySettings
import dev.aalhdydy550.tunnel.core.data.net.MAX_LOCAL_PROXY_PORT
import dev.aalhdydy550.tunnel.core.data.net.MIN_LOCAL_PROXY_PORT
import dev.aalhdydy550.tunnel.core.data.net.RESERVED_INJECTOR_SOCKS_PORT
import dev.aalhdydy550.tunnel.core.data.net.localProxyListenAddress
import dev.aalhdydy550.tunnel.core.data.net.validateLocalProxyPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the local proxy settings' pure validation logic:
 * the accepted port range, the reserved injector port 10810, and the
 * listen address for each sharing mode.
 */
class LocalProxySettingsTest {

    @Test
    fun `default port is 10808 and sharing is off`() {
        assertEquals(10808, DEFAULT_LOCAL_PROXY_PORT)
        assertEquals(LocalProxySettings(), LocalProxySettings(port = 10808, allowOtherDevices = false))
    }

    @Test
    fun `range bounds are 1024 and 65535`() {
        assertEquals(1024, MIN_LOCAL_PROXY_PORT)
        assertEquals(65535, MAX_LOCAL_PROXY_PORT)
    }

    @Test
    fun `reserved injector port is 10810`() {
        assertEquals(10810, RESERVED_INJECTOR_SOCKS_PORT)
    }

    @Test
    fun `valid ports pass validation`() {
        assertNull(validateLocalProxyPort(10808))
        assertNull(validateLocalProxyPort(1024))
        assertNull(validateLocalProxyPort(65535))
    }

    @Test
    fun `out of range ports fail validation`() {
        assertEquals("البورت يجب أن يكون بين 1024 و 65535.", validateLocalProxyPort(0))
        assertEquals("البورت يجب أن يكون بين 1024 و 65535.", validateLocalProxyPort(1023))
        assertEquals("البورت يجب أن يكون بين 1024 و 65535.", validateLocalProxyPort(65536))
        assertEquals("البورت يجب أن يكون بين 1024 و 65535.", validateLocalProxyPort(-1))
    }

    @Test
    fun `reserved port 10810 fails validation`() {
        assertEquals(
            "البورت 10810 محجوز لمسار «نت مجاني» (SOCKS الداخلي) — اختر بورتًا آخر.",
            validateLocalProxyPort(10810),
        )
        assertNotNull(validateLocalProxyPort(10810))
    }

    @Test
    fun `listen address is loopback when sharing is off`() {
        assertEquals("127.0.0.1", localProxyListenAddress(false))
    }

    @Test
    fun `listen address is all interfaces when sharing is on`() {
        assertEquals("0.0.0.0", localProxyListenAddress(true))
    }
}
