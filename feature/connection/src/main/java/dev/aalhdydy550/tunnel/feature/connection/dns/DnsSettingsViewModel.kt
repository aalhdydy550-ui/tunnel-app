package dev.aalhdydy550.tunnel.feature.connection.dns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.net.DnsPreference
import dev.aalhdydy550.tunnel.core.data.net.DnsPreset
import dev.aalhdydy550.tunnel.core.data.net.validateCustomDnsHost
import dev.aalhdydy550.tunnel.core.data.repository.DnsPreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Screen state for the DNS switcher screen. */
data class DnsUiState(
    val isLoading: Boolean = true,
    val preset: DnsPreset = DnsPreset.AUTO,
    val customHost: String = "",
    val customInput: String = "",
    val customError: String? = null,
) {
    /** The DoT address the current choice resolves to, or null when
     * the choice is AUTO (the engine probes the fastest resolver) or
     * a CUSTOM host that fails validation. */
    val effectiveDotAddress: String?
        get() = DnsPreference(preset, customHost).dotAddress
}

/**
 * DNS switcher screen state + actions.
 *
 * - The default is [DnsPreset.AUTO]: selecting it (or never opening
 *   the screen) keeps the engine's current behaviour — probe the
 *   public resolvers at connect time and use the fastest.
 * - Picking a known resolver pins it immediately; picking CUSTOM
 *   only persists once the typed host passes [validateCustomDnsHost],
 *   otherwise the error is shown and nothing is stored.
 * - The choice applies at the next connect (the config is built once
 *   per connection); the screen says so.
 */
@HiltViewModel
class DnsSettingsViewModel @Inject constructor(
    private val store: DnsPreferenceStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(DnsUiState())
    val uiState: StateFlow<DnsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.preference.collect { preference ->
                _uiState.update {
                    it.copy(
                        preset = preference.preset,
                        customHost = preference.customHost,
                        customInput = preference.customHost,
                        isLoading = false,
                    )
                }
            }
        }
    }

    fun onPresetSelected(preset: DnsPreset) {
        if (preset == DnsPreset.CUSTOM) {
            // Show the custom field; persist only when the stored
            // host is already valid, otherwise ask for a valid host
            // first — never persist a broken address.
            val host = _uiState.value.customHost
            if (validateCustomDnsHost(host) == null) {
                savePreference(DnsPreference(DnsPreset.CUSTOM, host.trim()))
            } else {
                _uiState.update {
                    it.copy(
                        preset = DnsPreset.CUSTOM,
                        customError = "أدخل خادم DNS مخصّصًا صالحًا أولًا.",
                    )
                }
            }
            return
        }
        _uiState.update { it.copy(preset = preset, customError = null) }
        savePreference(DnsPreference(preset, _uiState.value.customHost))
    }

    fun onCustomInputChange(text: String) {
        _uiState.update { it.copy(customInput = text, customError = null) }
    }

    /** Validates the typed host and, when valid, persists it. */
    fun onSaveCustom() {
        val host = _uiState.value.customInput.trim()
        val error = validateCustomDnsHost(host)
        if (error != null) {
            _uiState.update { it.copy(customError = error) }
            return
        }
        _uiState.update { it.copy(customHost = host, customError = null) }
        savePreference(DnsPreference(DnsPreset.CUSTOM, host))
    }

    private fun savePreference(preference: DnsPreference) {
        _uiState.update {
            it.copy(
                preset = preference.preset,
                customHost = preference.customHost,
                customInput = preference.customHost,
                customError = null,
            )
        }
        viewModelScope.launch { store.setPreference(preference) }
    }
}
