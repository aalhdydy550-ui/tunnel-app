package dev.aalhdydy550.tunnel.feature.connection.perapp

import android.graphics.drawable.Drawable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.repository.PerAppProxyStore
import dev.aalhdydy550.tunnel.core.data.routing.PerAppProxyMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Screen state for the per-app proxy screen. */
data class PerAppProxyUiState(
    val isLoading: Boolean = true,
    val apps: List<InstalledApp> = emptyList(),
    val query: String = "",
    val mode: PerAppProxyMode = PerAppProxyMode.ALL,
    val selectedPackages: Set<String> = emptySet(),
) {
    /** The list the screen shows: the query filter applied to [apps]. */
    val filteredApps: List<InstalledApp>
        get() = filterInstalledApps(apps, query)

    val selectedCount: Int
        get() = selectedPackages.size
}

/**
 * Filters the installed-apps list by display name or package name.
 * Case-insensitive; a blank query returns the list unchanged.
 * Pure function, unit-tested.
 */
fun filterInstalledApps(
    apps: List<InstalledApp>,
    query: String,
): List<InstalledApp> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return apps
    return apps.filter { app ->
        app.label.lowercase().contains(needle) ||
            app.packageName.lowercase().contains(needle)
    }
}

/**
 * Per-app proxy screen state + actions.
 *
 * - The installed-apps list loads once, off the main thread (see
 *   [InstalledAppsProvider]); the screen shows a loading state until
 *   it arrives.
 * - Every change (mode, toggle, select/clear) is saved to the store
 *   immediately — there is no separate "save" button to forget.
 * - The stored package set is kept across mode switches, so flipping
 *   between "selected only" and "except selected" never loses the
 *   list; in ALL mode the engine simply ignores it.
 * - The choice applies at the next connect (the config is built once
 *   per connection); the screen says so, so nobody expects a live
 *   switch while the tunnel runs.
 */
@HiltViewModel
class PerAppProxyViewModel @Inject constructor(
    private val store: PerAppProxyStore,
    private val appsSource: InstalledAppsSource,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PerAppProxyUiState())
    val uiState: StateFlow<PerAppProxyUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { settings ->
                _uiState.update {
                    it.copy(
                        mode = settings.mode,
                        selectedPackages = settings.packages,
                    )
                }
            }
        }
        viewModelScope.launch {
            val apps = runCatching { appsSource.load() }.getOrElse { emptyList() }
            _uiState.update { it.copy(apps = apps, isLoading = false) }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
    }

    fun onModeChange(mode: PerAppProxyMode) {
        _uiState.update { it.copy(mode = mode) }
        viewModelScope.launch { store.setMode(mode) }
    }

    fun onToggleApp(packageName: String) {
        val current = _uiState.value.selectedPackages
        val updated = if (packageName in current) {
            current - packageName
        } else {
            current + packageName
        }
        savePackages(updated)
    }

    /** Selects every app currently visible under the query filter. */
    fun onSelectAllFiltered() {
        val visible = _uiState.value.filteredApps.map { it.packageName }.toSet()
        savePackages(_uiState.value.selectedPackages + visible)
    }

    /** Clears every app currently visible under the query filter. */
    fun onClearFiltered() {
        val visible = _uiState.value.filteredApps.map { it.packageName }.toSet()
        savePackages(_uiState.value.selectedPackages - visible)
    }

    /** The row icon, loaded lazily by the screen (off the main thread). */
    fun loadIcon(packageName: String): Drawable? = appsSource.loadIcon(packageName)

    private fun savePackages(packages: Set<String>) {
        _uiState.update { it.copy(selectedPackages = packages) }
        viewModelScope.launch { store.setPackages(packages) }
    }
}
