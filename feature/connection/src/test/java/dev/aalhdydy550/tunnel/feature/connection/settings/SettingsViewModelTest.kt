package dev.aalhdydy550.tunnel.feature.connection.settings

import dev.aalhdydy550.tunnel.core.data.net.LocalProxySettings
import dev.aalhdydy550.tunnel.core.data.repository.AllowInsecureStore
import dev.aalhdydy550.tunnel.core.data.repository.LocalProxyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SettingsViewModel] with fake stores: default
 * loading, port validation, persistence, and the two immediate-save
 * toggles.
 *
 * `Dispatchers.Main` is an [UnconfinedTestDispatcher], so the
 * ViewModel's `viewModelScope` work runs synchronously and the state
 * can be asserted right after each action.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private class FakeLocalProxyStore(
        initial: LocalProxySettings = LocalProxySettings(),
    ) : LocalProxyStore {
        private val _settings = MutableStateFlow(initial)
        override val settings: Flow<LocalProxySettings> = _settings
        val savedPorts = mutableListOf<Int>()
        val savedAllow = mutableListOf<Boolean>()
        var setPortResult: Boolean = true

        override suspend fun setPort(port: Int): Boolean {
            if (!setPortResult) return false
            savedPorts += port
            _settings.value = _settings.value.copy(port = port)
            return true
        }

        override suspend fun setAllowOtherDevices(allow: Boolean) {
            savedAllow += allow
            _settings.value = _settings.value.copy(allowOtherDevices = allow)
        }
    }

    private class FakeAllowInsecureStore(
        initial: Boolean = false,
    ) : AllowInsecureStore {
        private val _enabled = MutableStateFlow(initial)
        override val enabled: Flow<Boolean> = _enabled
        val saved = mutableListOf<Boolean>()

        override suspend fun setEnabled(enabled: Boolean) {
            saved += enabled
            _enabled.value = enabled
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        store: FakeLocalProxyStore = FakeLocalProxyStore(),
        insecure: FakeAllowInsecureStore = FakeAllowInsecureStore(),
    ) = SettingsViewModel(store, insecure)

    @Test
    fun `init loads the stored defaults`() {
        val vm = viewModel()
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(10808, state.port)
        assertEquals("10808", state.portInput)
        assertNull(state.portError)
        assertFalse(state.allowOtherDevices)
        assertFalse(state.allowInsecure)
    }

    @Test
    fun `init restores stored values`() {
        val store = FakeLocalProxyStore(
            LocalProxySettings(port = 10809, allowOtherDevices = true),
        )
        val insecure = FakeAllowInsecureStore(initial = true)
        val vm = viewModel(store, insecure)
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(10809, state.port)
        assertEquals("10809", state.portInput)
        assertTrue(state.allowOtherDevices)
        assertTrue(state.allowInsecure)
    }

    @Test
    fun `saving an out-of-range or reserved port shows an error and saves nothing`() {
        val store = FakeLocalProxyStore()
        val vm = viewModel(store)
        for (bad in listOf(80, 10810)) {
            vm.onPortInputChange(bad.toString())
            vm.onSavePort()
            assertEquals(bad.toString(), vm.uiState.value.portInput)
            assertTrue(
                "expected an error for $bad",
                vm.uiState.value.portError != null,
            )
            assertEquals(10808, vm.uiState.value.port)
        }
        assertTrue(store.savedPorts.isEmpty())
    }

    @Test
    fun `saving non-numeric input shows an error and saves nothing`() {
        val store = FakeLocalProxyStore()
        val vm = viewModel(store)
        for (bad in listOf("abc", "", "12.5")) {
            vm.onPortInputChange(bad)
            vm.onSavePort()
            assertTrue(
                "expected an error for '$bad'",
                vm.uiState.value.portError != null,
            )
            assertEquals(10808, vm.uiState.value.port)
        }
        assertTrue(store.savedPorts.isEmpty())
    }

    @Test
    fun `saving a valid port persists it trimmed`() {
        val store = FakeLocalProxyStore()
        val vm = viewModel(store)
        vm.onPortInputChange("  10809  ")
        vm.onSavePort()
        assertEquals(10809, vm.uiState.value.port)
        assertEquals("10809", vm.uiState.value.portInput)
        assertNull(vm.uiState.value.portError)
        assertEquals(listOf(10809), store.savedPorts)
    }

    @Test
    fun `a failed save shows an error and keeps the old port`() {
        val store = FakeLocalProxyStore().also { it.setPortResult = false }
        val vm = viewModel(store)
        vm.onPortInputChange("10809")
        vm.onSavePort()
        assertTrue(vm.uiState.value.portError != null)
        assertEquals(10808, vm.uiState.value.port)
        assertTrue(store.savedPorts.isEmpty())
    }

    @Test
    fun `toggling LAN sharing saves immediately`() {
        val store = FakeLocalProxyStore()
        val vm = viewModel(store)
        vm.onAllowOtherDevicesChange(true)
        assertTrue(vm.uiState.value.allowOtherDevices)
        vm.onAllowOtherDevicesChange(false)
        assertFalse(vm.uiState.value.allowOtherDevices)
        assertEquals(listOf(true, false), store.savedAllow)
    }

    @Test
    fun `toggling allow insecure saves immediately`() {
        val insecure = FakeAllowInsecureStore()
        val vm = viewModel(insecure = insecure)
        vm.onAllowInsecureChange(true)
        assertTrue(vm.uiState.value.allowInsecure)
        assertEquals(listOf(true), insecure.saved)
        vm.onAllowInsecureChange(false)
        assertFalse(vm.uiState.value.allowInsecure)
        assertEquals(listOf(true, false), insecure.saved)
    }

    @Test
    fun `typing clears the previous error`() {
        val vm = viewModel()
        vm.onPortInputChange("80")
        vm.onSavePort()
        assertTrue(vm.uiState.value.portError != null)
        vm.onPortInputChange("1080")
        assertNull(vm.uiState.value.portError)
    }
}
