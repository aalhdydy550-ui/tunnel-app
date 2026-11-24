package dev.aalhdydy550.tunnel.feature.connection

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * اختبارات انتقالات [KillSwitchManager] — كل صف في جدول الانتقالات
 * الموثق في KDoc المدير، بما فيه الحالات الحدّية: لا حظر والمفتاح
 * مغلق، ولا حظر عند إيقاف المستخدم، والفشل المتأخر بعد الإيقاف
 * المقصود لا يعيد الحظر.
 */
class KillSwitchManagerTest {

    @Test
    fun `initial state is OFF with tun not kept`() {
        val manager = KillSwitchManager()

        assertEquals(KillSwitchState.OFF, manager.state)
        assertFalse(manager.shouldKeepTun)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `setEnabled true from OFF arms the switch`() {
        val manager = KillSwitchManager()

        manager.setEnabled(true)

        assertEquals(KillSwitchState.ARMED, manager.state)
        assertFalse(manager.shouldKeepTun)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `setEnabled false from ARMED turns OFF`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)

        manager.setEnabled(false)

        assertEquals(KillSwitchState.OFF, manager.state)
        assertFalse(manager.shouldKeepTun)
    }

    @Test
    fun `setEnabled false from OFF stays OFF`() {
        val manager = KillSwitchManager()

        manager.setEnabled(false)

        assertEquals(KillSwitchState.OFF, manager.state)
    }

    @Test
    fun `engine failure while disabled never blocks`() {
        val manager = KillSwitchManager()

        manager.onEngineFailed()

        assertEquals(KillSwitchState.OFF, manager.state)
        assertFalse(manager.shouldKeepTun)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `engine failure while armed blocks and keeps tun`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)

        manager.onEngineFailed()

        assertEquals(KillSwitchState.BLOCKING, manager.state)
        assertTrue(manager.shouldKeepTun)
        assertTrue(manager.isBlocking)
    }

    @Test
    fun `engine failure while already blocking stays blocking`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onEngineFailed()

        manager.onEngineFailed()

        assertEquals(KillSwitchState.BLOCKING, manager.state)
        assertTrue(manager.shouldKeepTun)
    }

    @Test
    fun `engine restart from blocking re-arms`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onEngineFailed()

        manager.onEngineStarted()

        assertEquals(KillSwitchState.ARMED, manager.state)
        assertFalse(manager.shouldKeepTun)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `engine start while enabled from OFF arms`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onStopped()

        manager.onEngineStarted()

        assertEquals(KillSwitchState.ARMED, manager.state)
    }

    @Test
    fun `engine start while disabled stays OFF`() {
        val manager = KillSwitchManager()

        manager.onEngineStarted()

        assertEquals(KillSwitchState.OFF, manager.state)
    }

    @Test
    fun `user stop from blocking turns OFF and releases tun`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onEngineFailed()

        manager.onStopped()

        assertEquals(KillSwitchState.OFF, manager.state)
        assertFalse(manager.shouldKeepTun)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `user stop from armed turns OFF`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)

        manager.onStopped()

        assertEquals(KillSwitchState.OFF, manager.state)
    }

    @Test
    fun `disabling from blocking turns OFF`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onEngineFailed()

        manager.setEnabled(false)

        assertEquals(KillSwitchState.OFF, manager.state)
        assertFalse(manager.shouldKeepTun)
        assertFalse(manager.isBlocking)
    }

    @Test
    fun `re-enabling from blocking stays blocking until engine restarts`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onEngineFailed()

        manager.setEnabled(true)

        assertEquals(KillSwitchState.BLOCKING, manager.state)
        assertTrue(manager.shouldKeepTun)
    }

    @Test
    fun `late engine failure after user stop does not re-block`() {
        val manager = KillSwitchManager()
        manager.setEnabled(true)
        manager.onEngineFailed()
        manager.onStopped()

        manager.onEngineFailed()

        assertEquals(KillSwitchState.OFF, manager.state)
        assertFalse(manager.shouldKeepTun)
    }

    @Test
    fun `full cycle OFF ARMED BLOCKING ARMED OFF`() {
        val manager = KillSwitchManager()

        manager.setEnabled(true)
        assertEquals(KillSwitchState.ARMED, manager.state)

        manager.onEngineFailed()
        assertEquals(KillSwitchState.BLOCKING, manager.state)
        assertTrue(manager.shouldKeepTun)

        manager.onEngineStarted()
        assertEquals(KillSwitchState.ARMED, manager.state)
        assertFalse(manager.shouldKeepTun)

        manager.onStopped()
        assertEquals(KillSwitchState.OFF, manager.state)
    }
}
