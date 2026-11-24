package dev.aalhdydy550.tunnel.feature.profiles

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.net.LatencyTester
import dev.aalhdydy550.tunnel.core.data.net.ProtocolSpeed
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.data.repository.SettingsRepository
import dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataManager
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataStatus
import dev.aalhdydy550.tunnel.core.data.routing.RoutingFallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Server list + settings for the main screen.
 *
 * Owns the latency test ("اختبار التأخير" — the fastest server wins,
 * with Hysteria2/WireGuard preferred on a tie), the battery-exemption
 * status, the kill-switch guidance flag, the ads-blocking toggle +
 * routing-data status (with retry), and the JSON backup export.
 *
 * Manual choice: tapping a server row marks it a manual selection, and
 * the next connect uses that server instead of auto-picking the
 * fastest (see [selectManually] / [consumeManualSelection]).
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val subscriptionRepository: SubscriptionRepository,
) : ViewModel() {

    val profiles: StateFlow<List<Profile>> = profileRepository.profiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val selectedProfileId: StateFlow<Long> = settingsRepository.selectedProfileId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1L)

    val autoStartOnBoot: StateFlow<Boolean> = settingsRepository.autoStartOnBoot
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val killSwitchGuided: StateFlow<Boolean> = settingsRepository.killSwitchGuided
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val adsBlocking: StateFlow<Boolean> = settingsRepository.adsBlocking
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * True when the list contains servers from the built-in default
     * free subscription — the servers screen then shows the public /
     * free warning (slow, unstable, never for logins or banking).
     */
    val hasPublicFreeServers: StateFlow<Boolean> = combine(
        profileRepository.profiles,
        subscriptionRepository.subscriptions,
    ) { profiles, subscriptions ->
        val defaultId = subscriptions.firstOrNull { it.isDefault }?.id
        defaultId != null && profiles.any { it.subscriptionId == defaultId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _latency = MutableStateFlow<Map<Long, Long?>>(emptyMap())
    val latency: StateFlow<Map<Long, Long?>> = _latency.asStateFlow()

    private val _latencyTesting = MutableStateFlow(false)
    val latencyTesting: StateFlow<Boolean> = _latencyTesting.asStateFlow()

    private val _selectingFastest = MutableStateFlow(false)
    /** True while the connect button is picking the fastest server. */
    val selectingFastest: StateFlow<Boolean> = _selectingFastest.asStateFlow()

    /** True after the user taps a server row by hand; the next connect
     * uses that server instead of auto-picking the fastest. */
    var manualSelection = false
        private set

    private val routingManager = RoutingDataManager(
        File(context.filesDir, RoutingDataManager.RULE_SETS_DIR_NAME),
    )

    private val _routingStatus = MutableStateFlow<RoutingDataStatus?>(null)
    val routingStatus: StateFlow<RoutingDataStatus?> = _routingStatus.asStateFlow()

    /** The fallback decision for the ads toggle (see RoutingFallback). */
    val routingDecision: StateFlow<RoutingFallback.Decision?> = combine(
        _routingStatus,
        adsBlocking,
    ) { status, ads ->
        status?.let { RoutingFallback.decide(it, ads) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _batteryExempt = MutableStateFlow<Boolean?>(null)
    val batteryExempt: StateFlow<Boolean?> = _batteryExempt.asStateFlow()

    init {
        refreshRoutingStatus()
        refreshBatteryExempt()
        // Best-effort first-run download of the routing data, in the
        // background. A failure here changes nothing: the fallback
        // (all traffic through the tunnel) is already the default.
        viewModelScope.launch {
            routingManager.ensureDownloaded()
            refreshRoutingStatus()
        }
    }

    fun select(profileId: Long) {
        viewModelScope.launch { settingsRepository.setSelectedProfileId(profileId) }
    }

    /** Manual pick from the server list: remembers it for the next
     * connect (which then skips the auto-fastest pick). */
    fun selectManually(profileId: Long) {
        manualSelection = true
        select(profileId)
    }

    /** Consumes the manual-pick flag (true = the next connect must use
     * the manually chosen server, not the fastest). */
    fun consumeManualSelection(): Boolean {
        val picked = manualSelection
        manualSelection = false
        return picked
    }

    fun delete(profile: Profile) {
        viewModelScope.launch { profileRepository.delete(profile) }
    }

    /** Probes every server's latency (TCP connect, like the original's testAllTcping). */
    fun testLatency() {
        if (_latencyTesting.value) return
        viewModelScope.launch {
            _latencyTesting.value = true
            val results = withContext(Dispatchers.IO) {
                LatencyTester.testAll(profiles.value)
            }
            _latency.value = results.associate { it.profile.id to it.latencyMs }
            _latencyTesting.value = false
        }
    }

    /** Tests latency, then selects the fastest server and returns it
     * (null when no server answers or a test is already running — the
     * caller then connects to the currently selected server). Awaits
     * the selection write so the caller can connect to the picked
     * server right away. Used by the connect button: pressing connect
     * first picks the fastest, then connects. */
    suspend fun selectFastestAndWait(): Profile? {
        if (_latencyTesting.value) return null
        _selectingFastest.value = true
        _latencyTesting.value = true
        val results = withContext(Dispatchers.IO) {
            LatencyTester.testAll(profiles.value)
        }
        _latency.value = results.associate { it.profile.id to it.latencyMs }
        _latencyTesting.value = false
        _selectingFastest.value = false
        val fastest = ProtocolSpeed.pickFastest(results)
        fastest?.let { settingsRepository.setSelectedProfileId(it.id) }
        return fastest
    }

    fun setAutoStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setAutoStartOnBoot(enabled) }
    }

    fun setAdsBlocking(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAdsBlocking(enabled)
            if (enabled) {
                routingManager.ensureDownloaded()
                refreshRoutingStatus()
            }
        }
    }

    fun markKillSwitchGuided() {
        viewModelScope.launch { settingsRepository.setKillSwitchGuided(true) }
    }

    fun retryRoutingDownload() {
        viewModelScope.launch {
            routingManager.ensureDownloaded()
            refreshRoutingStatus()
        }
    }

    fun refreshRoutingStatus() {
        _routingStatus.value = routingManager.status()
    }

    fun refreshBatteryExempt() {
        val powerManager =
            context.getSystemService(Context.POWER_SERVICE) as PowerManager
        _batteryExempt.value =
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** The whole profile list as a clean JSON backup document. */
    suspend fun exportBackup(): String = profileRepository.exportToJson()
}
