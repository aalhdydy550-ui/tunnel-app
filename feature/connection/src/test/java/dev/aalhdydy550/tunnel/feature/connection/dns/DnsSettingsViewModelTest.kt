package dev.aalhdydy550.tunnel.feature.connection.dns

import dev.aalhdydy550.tunnel.core.data.net.DnsPreference
import dev.aalhdydy550.tunnel.core.data.net.DnsPreset
import dev.aalhdydy550.tunnel.core.data.repository.DnsPreferenceStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [DnsSettingsViewModel] with a fake store: preset
 * selection, custom-host validation, persistence, and the effective
 * address.
 *
 * `Dispatchers.Main` is an [UnconfinedTestDispatcher], so the
 * ViewModel's `viewModelScope` work runs synchronously and the state
 * can be asserted right after each action.
 */
class DnsSettingsViewModelTest {

    private class FakeDnsPreferenceStore(
        initial: DnsPreference = DnsPreference(),
    ) : DnsPreferenceStore {
        private val _preference = MutableStateFlow(initial)
        override val preference: Flow<DnsPreference> = _preference
        val saved = mutableListOf<DnsPreference>()

        override suspend fun setPreference(preference: DnsPreference) {
            saved += preference
            _preference.value = preference
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
        store: FakeDnsPreferenceStore = FakeDnsPreferenceStore(),
    ) = DnsSettingsViewModel(store)

    @Test
    fun `init restores the stored preset`() {
        val store = FakeDnsPreferenceStore(DnsPreference(DnsPreset.GOOGLE))
        val vm = viewModel(store)
        val state = vm.uiState.value
        assertEquals(DnsPreset.GOOGLE, state.preset)
        assertEquals("tls://8.8.8.8", state.effectiveDotAddress)
        assertEquals(false, state.isLoading)
    }

    @Test
    fun `default is AUTO with no effective address`() {
        val vm = viewModel()
        assertEquals(DnsPreset.AUTO, vm.uiState.value.preset)
        assertNull(vm.uiState.value.effectiveDotAddress)
    }

    @Test
    fun `selecting a known preset persists it immediately`() {
        val store = FakeDnsPreferenceStore()
        val vm = viewModel(store)
        vm.onPresetSelected(DnsPreset.CLOUDFLARE)
        assertEquals(DnsPreset.CLOUDFLARE, vm.uiState.value.preset)
        assertEquals("tls://1.1.1.1", vm.uiState.value.effectiveDotAddress)
        assertEquals(
            listOf(DnsPreference(DnsPreset.CLOUDFLARE)),
            store.saved,
        )
    }

    @Test
    fun `selecting AUTO again restores the probe behaviour`() {
        val store = FakeDnsPreferenceStore(DnsPreference(DnsPreset.QUAD9))
        val vm = viewModel(store)
        vm.onPresetSelected(DnsPreset.AUTO)
        assertEquals(DnsPreset.AUTO, vm.uiState.value.preset)
        assertNull(vm.uiState.value.effectiveDotAddress)
        assertEquals(
            listOf(DnsPreference(DnsPreset.AUTO)),
            store.saved,
        )
    }

    @Test
    fun `saving a valid custom host persists it trimmed`() {
        val store = FakeDnsPreferenceStore()
        val vm = viewModel(store)
        vm.onPresetSelected(DnsPreset.CUSTOM)
        // No stored host yet: CUSTOM is shown but nothing persisted.
        assertTrue(store.saved.isEmpty())
        assertEquals(DnsPreset.CUSTOM, vm.uiState.value.preset)
        vm.onCustomInputChange("  9.9.9.9  ")
        vm.onSaveCustom()
        assertEquals(DnsPreset.CUSTOM, vm.uiState.value.preset)
        assertEquals("tls://9.9.9.9", vm.uiState.value.effectiveDotAddress)
        assertEquals(
            listOf(DnsPreference(DnsPreset.CUSTOM, "9.9.9.9")),
            store.saved,
        )
        assertNull(vm.uiState.value.customError)
    }

    @Test
    fun `saving an invalid custom host shows an error and persists nothing`() {
        val store = FakeDnsPreferenceStore()
        val vm = viewModel(store)
        vm.onPresetSelected(DnsPreset.CUSTOM)
        for (bad in listOf("", "http://1.1.1.1", "999.1.1.1", "foo bar")) {
            vm.onCustomInputChange(bad)
            vm.onSaveCustom()
            assertEquals(bad, vm.uiState.value.customInput)
            assertTrue(
                "expected an error for '$bad'",
                vm.uiState.value.customError != null,
            )
        }
        assertTrue(store.saved.isEmpty())
        // The preset stays CUSTOM on screen, but nothing was stored.
        assertEquals(DnsPreset.CUSTOM, vm.uiState.value.preset)
        assertNull(vm.uiState.value.effectiveDotAddress)
    }

    @Test
    fun `typing clears the previous error`() {
        val vm = viewModel()
        vm.onPresetSelected(DnsPreset.CUSTOM)
        vm.onCustomInputChange("")
        vm.onSaveCustom()
        assertTrue(vm.uiState.value.customError != null)
        vm.onCustomInputChange("9")
        assertNull(vm.uiState.value.customError)
    }

    @Test
    fun `selecting CUSTOM with a valid stored host persists at once`() {
        val store = FakeDnsPreferenceStore(
            DnsPreference(DnsPreset.GOOGLE, "9.9.9.9"),
        )
        val vm = viewModel(store)
        vm.onPresetSelected(DnsPreset.CUSTOM)
        assertEquals(
            listOf(DnsPreference(DnsPreset.CUSTOM, "9.9.9.9")),
            store.saved,
        )
        assertEquals("tls://9.9.9.9", vm.uiState.value.effectiveDotAddress)
    }
}
