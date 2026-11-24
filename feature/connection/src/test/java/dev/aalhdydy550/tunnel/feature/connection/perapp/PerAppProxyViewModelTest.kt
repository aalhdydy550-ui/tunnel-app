package dev.aalhdydy550.tunnel.feature.connection.perapp

import android.graphics.drawable.Drawable
import dev.aalhdydy550.tunnel.core.data.repository.PerAppProxyStore
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxyMode
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PerAppProxyViewModel] with fakes: the installed-apps
 * list, the query filter, mode changes, selection, and persistence.
 *
 * `Dispatchers.Main` is an [UnconfinedTestDispatcher], so the
 * ViewModel's `viewModelScope` work runs synchronously and the state
 * can be asserted right after each action.
 */
class PerAppProxyViewModelTest {

    private class FakePerAppProxyStore(
        initial: PerAppProxySettings = PerAppProxySettings(),
    ) : PerAppProxyStore {
        private val _settings = MutableStateFlow(initial)
        override val settings: Flow<PerAppProxySettings> = _settings
        val savedModes = mutableListOf<PerAppProxyMode>()
        val savedPackages = mutableListOf<Set<String>>()

        override suspend fun setMode(mode: PerAppProxyMode) {
            savedModes += mode
            _settings.value = _settings.value.copy(mode = mode)
        }

        override suspend fun setPackages(packages: Set<String>) {
            savedPackages += packages
            _settings.value = _settings.value.copy(packages = packages)
        }
    }

    private class FakeInstalledAppsSource(
        private val apps: List<InstalledApp>,
    ) : InstalledAppsSource {
        override suspend fun load(): List<InstalledApp> = apps
        override fun loadIcon(packageName: String): Drawable? = null
    }

    private val apps = listOf(
        InstalledApp("com.bank.app", "بنك مصر"),
        InstalledApp("com.chat.app", "دردشة"),
        InstalledApp("com.video.app", "فيديو"),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        store: FakePerAppProxyStore = FakePerAppProxyStore(),
        source: InstalledAppsSource = FakeInstalledAppsSource(apps),
    ) = PerAppProxyViewModel(store, source)

    @Test
    fun `init loads apps and restores saved settings`() {
        val store = FakePerAppProxyStore(
            PerAppProxySettings(
                mode = PerAppProxyMode.EXCLUDE_SELECTED,
                packages = setOf("com.bank.app"),
            ),
        )
        val vm = viewModel(store)
        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(apps, state.apps)
        assertEquals(PerAppProxyMode.EXCLUDE_SELECTED, state.mode)
        assertEquals(setOf("com.bank.app"), state.selectedPackages)
    }

    @Test
    fun `filter matches label case-insensitively`() {
        val vm = viewModel()
        vm.onQueryChange("فيديو")
        assertEquals(listOf(apps[2]), vm.uiState.value.filteredApps)
    }

    @Test
    fun `filter matches package name`() {
        val vm = viewModel()
        vm.onQueryChange("BANK")
        assertEquals(listOf(apps[0]), vm.uiState.value.filteredApps)
    }

    @Test
    fun `filter trims the query and blank returns everything`() {
        val vm = viewModel()
        vm.onQueryChange("  دردشة  ")
        assertEquals(listOf(apps[1]), vm.uiState.value.filteredApps)
        vm.onQueryChange("   ")
        assertEquals(apps, vm.uiState.value.filteredApps)
    }

    @Test
    fun `filter with no match returns empty`() {
        val vm = viewModel()
        vm.onQueryChange("غير موجود")
        assertTrue(vm.uiState.value.filteredApps.isEmpty())
    }

    @Test
    fun `toggle adds then removes and persists each time`() {
        val store = FakePerAppProxyStore()
        val vm = viewModel(store)
        vm.onToggleApp("com.chat.app")
        assertEquals(setOf("com.chat.app"), vm.uiState.value.selectedPackages)
        assertEquals(listOf(setOf("com.chat.app")), store.savedPackages)
        vm.onToggleApp("com.chat.app")
        assertTrue(vm.uiState.value.selectedPackages.isEmpty())
        assertEquals(
            listOf(setOf("com.chat.app"), emptySet()),
            store.savedPackages,
        )
    }

    @Test
    fun `mode change is saved and selection survives it`() {
        val store = FakePerAppProxyStore(
            PerAppProxySettings(packages = setOf("com.bank.app")),
        )
        val vm = viewModel(store)
        vm.onModeChange(PerAppProxyMode.INCLUDE_ONLY)
        assertEquals(PerAppProxyMode.INCLUDE_ONLY, vm.uiState.value.mode)
        assertEquals(listOf(PerAppProxyMode.INCLUDE_ONLY), store.savedModes)
        // The list is kept across the switch (engine ignores it in ALL).
        assertEquals(setOf("com.bank.app"), vm.uiState.value.selectedPackages)
        vm.onModeChange(PerAppProxyMode.ALL)
        assertEquals(setOf("com.bank.app"), vm.uiState.value.selectedPackages)
    }

    @Test
    fun `select-all and clear act only on the visible filter`() {
        val vm = viewModel()
        vm.onQueryChange("app")
        vm.onSelectAllFiltered()
        assertEquals(
            setOf("com.bank.app", "com.chat.app", "com.video.app"),
            vm.uiState.value.selectedPackages,
        )
        vm.onQueryChange("فيديو")
        vm.onClearFiltered()
        assertEquals(
            setOf("com.bank.app", "com.chat.app"),
            vm.uiState.value.selectedPackages,
        )
    }

    @Test
    fun `filter helper is pure`() {
        assertEquals(apps, filterInstalledApps(apps, ""))
        assertEquals(
            listOf(apps[1]),
            filterInstalledApps(apps, "chat"),
        )
    }
}
