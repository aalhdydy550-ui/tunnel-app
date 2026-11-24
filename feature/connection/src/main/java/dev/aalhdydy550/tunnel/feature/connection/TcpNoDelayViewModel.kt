package dev.aalhdydy550.tunnel.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.repository.TcpNoDelayRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TCP No Delay toggle (§4-#18), ready to bind to a settings screen.
 *
 * The value is off by default and only affects sockets the app opens
 * itself (see [TcpNoDelay]); toggling it never rebuilds the tunnel
 * config and never changes sing-box behaviour. A running session
 * picks the new value up on its next connect.
 */
@HiltViewModel
class TcpNoDelayViewModel @Inject constructor(
    private val repository: TcpNoDelayRepository,
) : ViewModel() {

    val enabled: StateFlow<Boolean> = repository.enabled.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        TcpNoDelay.DEFAULT_ENABLED,
    )

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(enabled) }
    }
}
