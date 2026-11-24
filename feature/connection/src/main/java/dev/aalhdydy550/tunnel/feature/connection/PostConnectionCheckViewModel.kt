package dev.aalhdydy550.tunnel.feature.connection

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Runs the post-connection check (#7) and holds its latest result.
 *
 * The check runs only while the tunnel is connected: automatically
 * once on every transition into [TunnelState.Started], and manually
 * via [checkNow] (the card's button — a no-op while disconnected).
 * The result is cleared on disconnect, so a stale "success" is never
 * shown for a dead tunnel. Each result is also written to
 * [TunnelEventLog], so it shows up in the diagnostics screen (#9).
 *
 * No periodic work: one check per connection plus manual re-checks.
 * The [OkHttpClient] comes from the shared Hilt graph (DataModule).
 */
@HiltViewModel
class PostConnectionCheckViewModel @Inject constructor(
    client: OkHttpClient,
) : ViewModel() {

    private val checker = PostConnectionChecker(client)

    private val _result = MutableStateFlow<PostConnectionChecker.Result?>(null)
    val result: StateFlow<PostConnectionChecker.Result?> = _result.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    init {
        viewModelScope.launch {
            var wasStarted = false
            TunnelStateHolder.state.collect { state ->
                val started = state is TunnelState.Started
                if (started && !wasStarted) runCheck()
                if (!started) _result.value = null
                wasStarted = started
            }
        }
    }

    /** Manual re-check from the card; ignored while disconnected. */
    fun checkNow() {
        if (TunnelStateHolder.state.value !is TunnelState.Started) return
        runCheck()
    }

    private fun runCheck() {
        if (_checking.value) return
        viewModelScope.launch {
            _checking.value = true
            try {
                val result = checker.check()
                _result.value = result
                TunnelEventLog.log(
                    "فحص ما بعد الاتصال: " + if (result.success) {
                        "ناجح — IP ${result.publicIp ?: "غير معروف"} — " +
                            "${result.latencyMs}ms"
                    } else {
                        "فاشل — لا يمر الترافيك عبر النفق"
                    },
                )
            } finally {
                _checking.value = false
            }
        }
    }
}
