package dev.aalhdydy550.tunnel.feature.connection

import android.content.Context
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.net.DnsSelector
import dev.aalhdydy550.tunnel.core.data.net.EndpointProbe
import dev.aalhdydy550.tunnel.core.data.net.SpeedTester
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.data.repository.SettingsRepository
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataManager
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Connection state + actions for the UI.
 *
 * The tunnel state itself lives in [TunnelStateHolder] (written by the
 * service); this ViewModel adds the selected profile, the live speed,
 * the optional speed test, and the connect/disconnect actions.
 *
 * Connect flow: if `VpnService.prepare()` returns null the permission
 * is already granted and the service starts directly; otherwise the
 * ViewModel emits [vpnPermissionRequest] and the UI launches the
 * system permission screen, then calls [onVpnPermissionGranted].
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val state: StateFlow<TunnelState> = TunnelStateHolder.state

    val speed: StateFlow<SpeedMonitor.Speed> = TunnelSpeedHolder.speed

    val selectedProfile: StateFlow<Profile?> = combine(
        profileRepository.profiles,
        settingsRepository.selectedProfileId,
    ) { profiles, selectedId ->
        profiles.firstOrNull { it.id == selectedId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _vpnPermissionRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val vpnPermissionRequest: SharedFlow<Unit> = _vpnPermissionRequest.asSharedFlow()

    private val _speedTestResult = MutableStateFlow<SpeedTester.Result?>(null)
    val speedTestResult: StateFlow<SpeedTester.Result?> = _speedTestResult.asStateFlow()

    private val _speedTestRunning = MutableStateFlow(false)
    val speedTestRunning: StateFlow<Boolean> = _speedTestRunning.asStateFlow()

    private val ruleSetsDir: File
        get() = File(context.filesDir, RoutingDataManager.RULE_SETS_DIR_NAME)

    /** Starts the tunnel for the selected profile, asking for VPN permission first. */
    fun connect() {
        viewModelScope.launch {
            val profile = selectedProfile.value ?: return@launch
            if (VpnService.prepare(context) != null) {
                _vpnPermissionRequest.emit(Unit)
                return@launch
            }
            startServiceFor(profile)
        }
    }

    /** Called by the UI after the user granted the VPN permission. */
    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            val profile = selectedProfile.value ?: return@launch
            startServiceFor(profile)
        }
    }

    fun disconnect() {
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.stopIntent(context),
        )
    }

    /** Optional in-app download speed test (through the tunnel when connected). */
    fun runSpeedTest() {
        if (_speedTestRunning.value) return
        viewModelScope.launch {
            _speedTestRunning.value = true
            _speedTestResult.value = withContext(Dispatchers.IO) {
                runCatching { SpeedTester().test() }.getOrNull()
            }
            _speedTestRunning.value = false
        }
    }

    private suspend fun startServiceFor(profile: Profile) {
        // Fastest public DNS resolver at connect time (best-effort,
        // ~1.5s budget); falls back to tls://1.1.1.1 when none answers.
        val dnsServer = withContext(Dispatchers.IO) {
            DnsSelector.selectFastest()?.dotAddress
                ?: SingBoxConfigBuilder.DEFAULT_DNS_SERVER
        }
        val config = SingBoxConfigBuilder.buildConfig(
            profile = profile,
            autoRoute = settingsRepository.autoRoute.first(),
            strictRoute = settingsRepository.strictRoute.first(),
            adsBlocking = settingsRepository.adsBlocking.first(),
            ruleSetsDir = ruleSetsDir.absolutePath,
            dnsServer = dnsServer,
        )
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.startIntent(
                context = context,
                profileId = profile.id,
                profileName = profile.name,
                profileType = profile.type.name,
                configJson = config,
            ),
        )
    }
}
