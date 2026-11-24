package dev.aalhdydy550.tunnel.feature.connection.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.net.DEFAULT_LOCAL_PROXY_PORT
import dev.aalhdydy550.tunnel.core.data.net.validateLocalProxyPort
import dev.aalhdydy550.tunnel.core.data.repository.AllowInsecureStore
import dev.aalhdydy550.tunnel.core.data.repository.KillSwitchStore
import dev.aalhdydy550.tunnel.core.data.repository.LocalProxyStore
import dev.aalhdydy550.tunnel.core.data.repository.ProxyMode
import dev.aalhdydy550.tunnel.core.data.repository.ProxyModeStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Screen state for the Settings screen. */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val port: Int = DEFAULT_LOCAL_PROXY_PORT,
    val portInput: String = DEFAULT_LOCAL_PROXY_PORT.toString(),
    val portError: String? = null,
    val allowOtherDevices: Boolean = false,
    val allowInsecure: Boolean = false,
    /** Connection mode: [ProxyMode.VPN] (default) or proxy-only. */
    val proxyMode: ProxyMode = ProxyMode.VPN,
    /** In-app kill switch: block all traffic while the tunnel is down. */
    val killSwitchEnabled: Boolean = false,
)

/**
 * Settings screen state + actions.
 *
 * - The local proxy port is only persisted once the typed value passes
 *   [validateLocalProxyPort]; otherwise the error is shown and nothing
 *   is stored. The port applies at the next connect.
 * - LAN sharing ([onAllowOtherDevicesChange]) and allow-insecure
 *   ([onAllowInsecureChange]) save immediately on every toggle, like
 *   the other settings screens; the screen warns about both.
 * - The connection mode ([onProxyModeChange]) also saves
 *   immediately: VPN (the default, whole-device tunnel) or
 *   proxy-only (no VPN interface — only apps configured to use the
 *   local proxy go through the tunnel). It applies at the next
 *   connect.
 * - The kill switch ([onKillSwitchChange]) also saves immediately
 *   and applies at the next connect. It is OFF by default and never
 *   works in proxy mode: with no TUN there is nothing to hold open,
 *   so the UI disables it there. It does not survive the death of
 *   the whole process — the stronger cover remains the system's
 *   Always-on VPN.
 * - The stored port is adopted into the text field only when it
 *   actually changes, so toggling a switch never wipes a port the user
 *   is still typing.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: LocalProxyStore,
    private val allowInsecureStore: AllowInsecureStore,
    private val proxyModeStore: ProxyModeStore,
    private val killSwitchStore: KillSwitchStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            store.settings.collect { settings ->
                _uiState.update { current ->
                    // Adopt the stored port into the field only when it
                    // changed (a successful save, or an external change);
                    // otherwise keep the text the user is editing.
                    val portInput = if (settings.port != current.port) {
                        settings.port.toString()
                    } else {
                        current.portInput
                    }
                    current.copy(
                        port = settings.port,
                        portInput = portInput,
                        allowOtherDevices = settings.allowOtherDevices,
                        isLoading = false,
                    )
                }
            }
        }
        viewModelScope.launch {
            allowInsecureStore.enabled.collect { enabled ->
                _uiState.update { it.copy(allowInsecure = enabled, isLoading = false) }
            }
        }
        viewModelScope.launch {
            proxyModeStore.mode.collect { mode ->
                _uiState.update { it.copy(proxyMode = mode, isLoading = false) }
            }
        }
        viewModelScope.launch {
            killSwitchStore.enabled.collect { enabled ->
                _uiState.update { it.copy(killSwitchEnabled = enabled, isLoading = false) }
            }
        }
    }

    fun onPortInputChange(text: String) {
        _uiState.update { it.copy(portInput = text, portError = null) }
    }

    /** Validates the typed port and, when valid, persists it. */
    fun onSavePort() {
        val raw = _uiState.value.portInput.trim()
        val port = raw.toIntOrNull()
        if (port == null) {
            _uiState.update {
                it.copy(portError = "أدخل رقم بورت صحيحًا — مثال: 10808")
            }
            return
        }
        val error = validateLocalProxyPort(port)
        if (error != null) {
            _uiState.update { it.copy(portError = error) }
            return
        }
        viewModelScope.launch {
            val saved = store.setPort(port)
            if (saved) {
                _uiState.update {
                    it.copy(
                        port = port,
                        portInput = port.toString(),
                        portError = null,
                    )
                }
            } else {
                _uiState.update {
                    it.copy(portError = "تعذّر حفظ البورت — حاول مجددًا.")
                }
            }
        }
    }

    fun onAllowOtherDevicesChange(allow: Boolean) {
        _uiState.update { it.copy(allowOtherDevices = allow) }
        viewModelScope.launch { store.setAllowOtherDevices(allow) }
    }

    fun onAllowInsecureChange(allow: Boolean) {
        _uiState.update { it.copy(allowInsecure = allow) }
        viewModelScope.launch { allowInsecureStore.setEnabled(allow) }
    }

    fun onProxyModeChange(mode: ProxyMode) {
        _uiState.update { it.copy(proxyMode = mode) }
        viewModelScope.launch { proxyModeStore.setMode(mode) }
    }

    fun onKillSwitchChange(enabled: Boolean) {
        _uiState.update { it.copy(killSwitchEnabled = enabled) }
        viewModelScope.launch { killSwitchStore.setEnabled(enabled) }
    }
}
