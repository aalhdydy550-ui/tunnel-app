package dev.aalhdydy550.tunnel.core.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import dev.aalhdydy550.tunnel.core.data.net.LocalProxySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the local proxy settings store: the defaults
 * (port 10808, LAN sharing off) and round-trips through the real
 * DataStore file — including that invalid ports are rejected and
 * leave the saved port untouched.
 */
class LocalProxyStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var store: LocalProxyStore

    @Before
    fun setUp() {
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) {
            File(tempFolder.newFolder(), "test-local-proxy.preferences_pb")
        }
        store = DataStoreLocalProxyStore(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults are port 10808 and sharing off`() = runTest {
        assertEquals(LocalProxySettings(port = 10808, allowOtherDevices = false), store.settings.first())
    }

    @Test
    fun `saving a valid port persists`() = runTest {
        assertTrue(store.setPort(18080))
        assertEquals(18080, store.settings.first().port)
    }

    @Test
    fun `saving the reserved port 10810 fails and keeps the old port`() = runTest {
        assertTrue(store.setPort(18080))
        assertFalse(store.setPort(10810))
        assertEquals(18080, store.settings.first().port)
    }

    @Test
    fun `saving an out of range port fails and keeps the old port`() = runTest {
        assertFalse(store.setPort(80))
        assertEquals(10808, store.settings.first().port)
    }

    @Test
    fun `toggling sharing persists`() = runTest {
        store.setAllowOtherDevices(true)
        assertTrue(store.settings.first().allowOtherDevices)
        store.setAllowOtherDevices(false)
        assertFalse(store.settings.first().allowOtherDevices)
    }
}
