package dev.aalhdydy550.tunnel.feature.profiles

import android.content.Context
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.groups.ConnectionGroup
import dev.aalhdydy550.tunnel.core.data.groups.GroupAutoSelect
import dev.aalhdydy550.tunnel.core.data.groups.GroupMode
import dev.aalhdydy550.tunnel.core.data.groups.GroupStore
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.SpeedTestResult
import dev.aalhdydy550.tunnel.core.data.net.LatencyTester
import dev.aalhdydy550.tunnel.core.data.net.ProtocolSpeed
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.data.repository.SettingsRepository
import dev.aalhdydy550.tunnel.core.data.repository.SpeedTestStore
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataManager
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataStatus
import dev.aalhdydy550.tunnel.core.data.routing.RoutingFallback
import dev.aalhdydy550.tunnel.feature.connection.SpeedTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
 *
 * Item 6 adds the connection groups (create/rename/delete + membership
 * + mode + active group, all delegated to [GroupStore]) and the manual
 * speed test ([runSpeedTest] — user-triggered only, tunnel only).
 */
@HiltViewModel
class ProfilesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val groupStore: GroupStore,
    private val speedTestStore: SpeedTestStore,
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

    /** Connection groups (item 6b) + the active group, if any. */
    val groups: StateFlow<List<ConnectionGroup>> = groupStore.groups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeGroupId: StateFlow<String?> = groupStore.activeGroupId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Last measured speed per profile (item 6a). */
    val speedResults: StateFlow<Map<Long, SpeedTestResult>> = speedTestStore.allResults()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _speedTestRunning = MutableStateFlow(false)
    /** True while a manual speed test is running. */
    val speedTestRunning: StateFlow<Boolean> = _speedTestRunning.asStateFlow()

    private val _speedTestProgress = MutableStateFlow<SpeedTestProgress?>(null)
    /** Live progress of the running speed test, or null when idle. */
    val speedTestProgress: StateFlow<SpeedTestProgress?> = _speedTestProgress.asStateFlow()

    private val _speedTestError = MutableStateFlow<String?>(null)
    /** Arabic speed-test failure message, or null. */
    val speedTestError: StateFlow<String?> = _speedTestError.asStateFlow()

    private var speedTestJob: Job? = null

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

    /** Probes every TCP-probeable server's latency (TCP connect,
     * like the original's testAllTcping). UDP-only servers are never
     * probed and get no latency entry (see [LatencyTester]). */
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

    /** Tests latency, then selects the fastest server and returns it.
     * When no server answers — the normal case for a list of UDP-only
     * servers, which are never TCP-probed — falls back to the
     * currently selected server, then to the first server in the
     * list, so the connect button always has a server to connect to
     * instead of doing nothing. Returns null only when the server
     * list is empty. When a test is already running (it starts
     * automatically when the server list opens), waits for its
     * results instead of giving up with null — the connect button
     * must always have a server. This non-null guarantee (one
     * profile ⇒ one server) is the item-6 precondition for the group
     * failover: a group connect never starts from "no server".
     * Awaits the selection write so the caller can connect to the
     * picked server right away. Used by the connect button: pressing
     * connect first picks the fastest, then connects. */
    suspend fun selectFastestAndWait(): Profile? {
        if (profiles.value.isEmpty()) return null
        _selectingFastest.value = true
        try {
            if (_latencyTesting.value) {
                // A test is already running — wait for its results
                // (a test takes ~3s: one TCP timeout at most) instead
                // of returning null. Bounded: after the wait, the
                // fallback in pickFromLatency (selected, then first)
                // still returns a server even if the test never
                // finished in time.
                var waitedMs = 0
                while (_latencyTesting.value && waitedMs < LATENCY_WAIT_TIMEOUT_MS) {
                    delay(100)
                    waitedMs += 100
                }
            } else {
                _latencyTesting.value = true
                try {
                    val results = withContext(Dispatchers.IO) {
                        LatencyTester.testAll(profiles.value)
                    }
                    _latency.value = results.associate { it.profile.id to it.latencyMs }
                } finally {
                    _latencyTesting.value = false
                }
            }
            val selected = pickFromLatency()
            selected?.let { settingsRepository.setSelectedProfileId(it.id) }
            return selected
        } finally {
            _selectingFastest.value = false
        }
    }

    /** Fastest profile from the last measured latencies ([_latency]):
     * lowest latency first, then the protocol rank — falling back to
     * the selected profile, then to the first profile, so the result
     * is never null while the list is non-empty. */
    private fun pickFromLatency(): Profile? {
        val results = profiles.value.map { profile ->
            LatencyTester.LatencyResult(profile, _latency.value[profile.id])
        }
        val selected = profiles.value.firstOrNull { it.id == selectedProfileId.value }
        return ProtocolSpeed.pickFastest(
            results,
            fallback = selected ?: profiles.value.firstOrNull(),
        )
    }

    // ------------------------------------------------------- groups (6b)

    /** Creates a group; blank names are ignored. */
    fun createGroup(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { groupStore.createGroup(trimmed) }
    }

    fun renameGroup(groupId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { groupStore.renameGroup(groupId, trimmed) }
    }

    fun deleteGroup(groupId: String) {
        viewModelScope.launch { groupStore.deleteGroup(groupId) }
    }

    fun addProfileToGroup(groupId: String, profileId: Long) {
        viewModelScope.launch { groupStore.addProfile(groupId, profileId) }
    }

    fun removeProfileFromGroup(groupId: String, profileId: Long) {
        viewModelScope.launch { groupStore.removeProfile(groupId, profileId) }
    }

    fun setGroupMode(groupId: String, mode: GroupMode) {
        viewModelScope.launch { groupStore.setMode(groupId, mode) }
    }

    fun setActiveGroup(groupId: String?) {
        viewModelScope.launch { groupStore.setActiveGroup(groupId) }
    }

    /**
     * Picks a random server, preferring recently successful ones
     * ([GroupAutoSelect] RANDOM weighting: last good profile +
     * measured speeds first): from the active group when one is set,
     * otherwise from all servers. The group's own mode is ignored —
     * this button is always random. Returns null only when there
     * are no servers at all.
     */
    fun pickRandom(): Profile? {
        val all = profiles.value
        if (all.isEmpty()) return null
        val active = groups.value.firstOrNull { it.id == activeGroupId.value }
        val group = (active ?: ConnectionGroup(
            id = "all",
            name = "كل الخوادم",
            profileIds = all.map { it.id },
        )).copy(mode = GroupMode.RANDOM)
        val speeds = speedResults.value.mapValues { it.value.downloadMbps }
        return GroupAutoSelect.pick(group, all, latencyMap(), speeds)
    }

    /** Measured latencies without the unanswered (null) entries —
     * [GroupAutoSelect] takes `Map<Long, Long>`. */
    private fun latencyMap(): Map<Long, Long> =
        _latency.value.entries.mapNotNull { (id, ms) -> ms?.let { id to it } }.toMap()

    // --------------------------------------------------- speed test (6a)

    /**
     * Runs a download speed test for [profileId] — user-triggered
     * only, and only while a tunnel is up ([isTunnelConnected] comes
     * from the UI, which shows the button only on a connected
     * server; [SpeedTest] itself also refuses to start without one).
     * The result is saved in [SpeedTestStore] (shown in the server
     * row) and, when the profile belongs to the active group,
     * recorded in the group for the smart pick. A Cloudflare failure
     * surfaces as a clear message — never a fake number.
     */
    fun runSpeedTest(profileId: Long, isTunnelConnected: Boolean) {
        if (_speedTestRunning.value) return
        if (!isTunnelConnected) {
            _speedTestError.value = "لا يوجد اتصال نفق قائم — اتصل أولًا ثم أعد الاختبار."
            return
        }
        if (profiles.value.none { it.id == profileId }) return
        speedTestJob = viewModelScope.launch {
            _speedTestRunning.value = true
            _speedTestError.value = null
            _speedTestProgress.value = null
            try {
                val result = SpeedTest().runDownload(
                    isTunnelConnected = true,
                    onProgress = { bytes, elapsed, mbps ->
                        _speedTestProgress.value = SpeedTestProgress(bytes, elapsed, mbps)
                    },
                )
                speedTestStore.saveResult(profileId, result)
                val active = groups.value.firstOrNull { it.id == activeGroupId.value }
                if (active != null && profileId in active.profileIds) {
                    groupStore.recordSpeed(active.id, profileId, result.downloadMbps)
                }
            } catch (e: TimeoutCancellationException) {
                // The 10s measurement cap (not a user cancel): a
                // failure to report, not a silent stop.
                _speedTestError.value = "انتهت مهلة الاختبار (10 ثوانٍ) — حاول مجددًا."
            } catch (e: CancellationException) {
                // Cancelled by the user — no error, no fake result.
                _speedTestProgress.value = null
                throw e
            } catch (e: IllegalStateException) {
                _speedTestError.value = "لا يوجد اتصال نفق قائم — اتصل أولًا ثم أعد الاختبار."
            } catch (e: IOException) {
                _speedTestError.value = "تعذّر الوصول إلى نقطة اختبار السرعة — تحقق من الاتصال وحاول مجددًا."
            } finally {
                _speedTestRunning.value = false
            }
        }
    }

    fun cancelSpeedTest() {
        speedTestJob?.cancel()
    }

    fun clearSpeedTestError() {
        _speedTestError.value = null
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

    companion object {
        /** How long [selectFastestAndWait] waits for an already-running
         * latency test before falling back (a test takes ~3s max: one
         * TCP timeout). */
        private const val LATENCY_WAIT_TIMEOUT_MS = 10_000
    }
}

/** Live progress of a running speed test (item 6a). */
data class SpeedTestProgress(
    val downloadedBytes: Long,
    val elapsedMs: Long,
    val mbps: Double,
)
