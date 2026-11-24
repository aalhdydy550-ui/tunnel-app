package dev.aalhdydy550.tunnel.feature.connection.injector

import android.content.Context
import android.net.VpnService
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.database.TunnelDatabaseHolder
import dev.aalhdydy550.tunnel.feature.connection.TunnelState
import dev.aalhdydy550.tunnel.feature.connection.TunnelStateHolder
import dev.aalhdydy550.tunnel.feature.connection.TunnelVpnService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject

/**
 * An SSH profile from the user's own saved profiles, parsed and ready
 * to connect. Only profiles whose outbound JSON carries all four fields
 * qualify; anything missing a field is excluded (see [FreeInternetViewModel]
 * — the exclusion count is shown in the UI).
 */
data class SshProfileOption(
    val profile: Profile,
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
)

/** Result of the post-connect traffic probe. */
sealed interface ProbeResult {
    data object Idle : ProbeResult
    data object Checking : ProbeResult
    data object Success : ProbeResult
    data class Failed(val message: String) : ProbeResult
}

/**
 * "Free internet" (SNI + SSH tunnel) screen state + actions.
 *
 * - Detects the SIM carrier with `TelephonyManager.getSimOperator()`
 *   (no permission of any kind); the user can override it by hand.
 * - Lists the injection candidates from the reference table and the
 *   user's own SSH profiles (imported from the existing import screen).
 * - [connect] builds the injector config, asks for VPN permission if
 *   needed, then starts the tunnel through the engine's injector entry
 *   point. Nothing connects before the user presses connect.
 * - Once the tunnel reports [TunnelState.Started] under the name of the
 *   network being tried, the traffic probe runs exactly once: on
 *   success the network is stored as verified on this SIM; on failure
 *   a plain message is shown and nothing is stored.
 *
 * The injector engine pieces ([InjectorConfig] and
 * `TunnelVpnService.startInjectorIntent`) are owned by the engine
 * worker; this ViewModel only calls them per the agreed contract — it
 * defines no copy of their constants or builders. (The SOCKS port
 * constant lives in `InjectorConstants` and is consumed inside
 * `InjectorConfig` itself, so this side passes no port anywhere.)
 */
@HiltViewModel
class FreeInternetViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
) : ViewModel() {

    private val repository = FreeInternetRepository(
        TunnelDatabaseHolder.get(context).referenceNetworkDao(),
    )
    private val verifiedStore: VerifiedNetworkStore =
        SharedPreferencesVerifiedNetworkStore(context)
    private val probe = InjectorProbe()

    /** Live tunnel state, written by the service. */
    val tunnelState: StateFlow<TunnelState> = TunnelStateHolder.state

    // ------------------------------------------------------------ carrier

    /** Carrier read from the SIM, or UNKNOWN when no SIM can be read. */
    private val _detectedCarrier = MutableStateFlow(detectCarrier())
    val detectedCarrier: StateFlow<Carrier> = _detectedCarrier.asStateFlow()

    /** Effective carrier: the user's manual choice, or the detected one. */
    private val _selectedCarrier = MutableStateFlow(_detectedCarrier.value)
    val selectedCarrier: StateFlow<Carrier> = _selectedCarrier.asStateFlow()

    fun selectCarrier(carrier: Carrier) {
        _selectedCarrier.value = carrier
    }

    // ---------------------------------------------------------- candidates

    private val _rawCandidates = MutableStateFlow<List<NetworkCandidate>>(emptyList())

    private val _verifiedIds =
        MutableStateFlow<Set<Long>>(verifiedStore.allVerified())

    private val _verifiedDates = MutableStateFlow<Map<Long, Long>>(loadVerifiedDates())
    /** networkId → epoch millis when it was verified on this SIM. */
    val verifiedDates: StateFlow<Map<Long, Long>> = _verifiedDates.asStateFlow()

    /** Candidates display-ordered: SIM-carrier match, verified, name. */
    val candidates: StateFlow<List<NetworkCandidate>> = combine(
        _rawCandidates,
        _selectedCarrier,
        _verifiedIds,
    ) { raw, carrier, verified ->
        repository.sortCandidates(raw, carrier, verified)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedNetworkId = MutableStateFlow<Long?>(null)

    val selectedNetwork: StateFlow<NetworkCandidate?> = combine(
        _rawCandidates,
        _selectedNetworkId,
    ) { list, id -> list.firstOrNull { it.id == id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun selectNetwork(id: Long) {
        _selectedNetworkId.value = id
        // A new network means a fresh probe outcome.
        _probeResult.value = ProbeResult.Idle
    }

    // ------------------------------------------------------- SSH profiles

    /** Usable SSH profiles only (complete outbound JSON). */
    val sshProfiles: StateFlow<List<SshProfileOption>> = profileRepository.profiles
        .map { list ->
            list.filter { it.type == ProfileType.SSH }.mapNotNull { parseSshProfile(it) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** SSH profiles skipped because their outbound JSON is incomplete. */
    val excludedSshCount: StateFlow<Int> = profileRepository.profiles
        .map { list ->
            list.count { it.type == ProfileType.SSH && parseSshProfile(it) == null }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _selectedSshProfile = MutableStateFlow<SshProfileOption?>(null)
    val selectedSshProfile: StateFlow<SshProfileOption?> =
        _selectedSshProfile.asStateFlow()

    fun selectSshProfile(option: SshProfileOption) {
        _selectedSshProfile.value = option
    }

    // -------------------------------------------------------------- probe

    private val _probeResult = MutableStateFlow<ProbeResult>(ProbeResult.Idle)
    val probeResult: StateFlow<ProbeResult> = _probeResult.asStateFlow()

    // --------------------------------------------------- VPN permission

    private val _vpnPermissionRequest = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val vpnPermissionRequest: SharedFlow<Unit> = _vpnPermissionRequest.asSharedFlow()

    /** The network + SSH pair [connect] was given, for re-connect after
     * the VPN permission screen. */
    private var pendingConnect: PendingInjectorConnect? = null

    /** Name of the network currently being tried (matches the
     * [TunnelState.Started] profile name the service reports). */
    private var probePendingFor: String? = null
    private var probeDoneForAttempt = false

    private data class PendingInjectorConnect(
        val network: NetworkCandidate,
        val ssh: SshProfileOption,
    )

    init {
        viewModelScope.launch {
            _rawCandidates.value = repository.loadCandidates()
        }
        viewModelScope.launch {
            TunnelStateHolder.state.collect { state ->
                val name = probePendingFor ?: return@collect
                if (state is TunnelState.Started &&
                    state.profileName == name &&
                    !probeDoneForAttempt
                ) {
                    probeDoneForAttempt = true
                    runProbe(name)
                }
            }
        }
    }

    // ------------------------------------------------------------ actions

    /** Starts the injector tunnel for the selected network + SSH profile. */
    fun connect() {
        val network = selectedNetwork.value ?: return
        val ssh = _selectedSshProfile.value ?: return
        viewModelScope.launch {
            pendingConnect = PendingInjectorConnect(network, ssh)
            if (VpnService.prepare(context) != null) {
                _vpnPermissionRequest.emit(Unit)
                return@launch
            }
            startInjector(network, ssh)
        }
    }

    /** Called by the UI after the user granted the VPN permission. */
    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            val pending = pendingConnect ?: return@launch
            startInjector(pending.network, pending.ssh)
        }
    }

    fun disconnect() {
        probePendingFor = null
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.stopIntent(context),
        )
    }

    // ------------------------------------------------------------ helpers

    private fun startInjector(network: NetworkCandidate, ssh: SshProfileOption) {
        // The engine's delivered builder takes no parameters: the
        // SOCKS port (InjectorConstants.INJECTOR_SOCKS_PORT) is baked
        // into the document itself by InjectorConfig, and the SSH/SNI
        // parameters travel as Intent extras (see startInjectorIntent).
        val configJson = InjectorConfig.buildInjectorConfig()
        probePendingFor = network.name
        probeDoneForAttempt = false
        _probeResult.value = ProbeResult.Idle
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.startInjectorIntent(
                context,
                ssh.host,
                ssh.port,
                ssh.user,
                ssh.password,
                network.sniHost,
                network.id,
                network.name,
                configJson,
            ),
        )
    }

    /**
     * Runs the traffic probe once, then — and only then — stores the
     * network as verified. The tunnel must still be up under the same
     * name when the probe answers, otherwise a direct (non-tunnel)
     * success could falsely verify the network.
     */
    private fun runProbe(networkName: String) {
        _probeResult.value = ProbeResult.Checking
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { probe.probe() }
            val network = _rawCandidates.value.firstOrNull { it.name == networkName }
                ?: run {
                    // The network vanished from the candidates between
                    // connect and the probe answer: clear the pending
                    // flag so the UI does not stay stuck on "Checking",
                    // and do NOT verify anything.
                    probePendingFor = null
                    _probeResult.value = ProbeResult.Failed(
                        "تعذّر العثور على الشبكة — أعد المحاولة",
                    )
                    return@launch
                }
            val stillUp = TunnelStateHolder.state.value.let {
                it is TunnelState.Started && it.profileName == networkName
            }
            if (ok && stillUp) {
                verifiedStore.markVerified(network.id, network.carrier)
                _verifiedIds.value = verifiedStore.allVerified()
                _verifiedDates.value = loadVerifiedDates()
                _probeResult.value = ProbeResult.Success
            } else {
                _probeResult.value = ProbeResult.Failed(
                    "النفق يعمل لكن الفحص فشل — تحقق من الباقة",
                )
            }
            probePendingFor = null
        }
    }

    /** SIM carrier via `getSimOperator()` ("60202" etc). No permission. */
    private fun detectCarrier(): Carrier = runCatching {
        val telephony = context.getSystemService(Context.TELEPHONY_SERVICE)
            as? TelephonyManager ?: return Carrier.UNKNOWN
        CarrierDetector.fromMccMnc(telephony.simOperator)
    }.getOrDefault(Carrier.UNKNOWN)

    private fun loadVerifiedDates(): Map<Long, Long> =
        verifiedStore.allVerified().mapNotNull { id ->
            verifiedStore.verifiedAt(id)?.let { id to it }
        }.toMap()

    /**
     * Parses an SSH profile's outbound JSON (`server`, `server_port`,
     * `user`, `password`). Returns null when any field is missing or
     * invalid — the profile is then excluded from the picker.
     */
    private fun parseSshProfile(profile: Profile): SshProfileOption? = runCatching {
        val json = JSONObject(profile.outboundJson)
        val host = json.optString("server", "").trim()
        val port = json.optInt("server_port", 0)
        val user = json.optString("user", "").trim()
        val password = json.optString("password", "")
        if (host.isEmpty() || port <= 0 || user.isEmpty() || password.isEmpty()) {
            return null
        }
        SshProfileOption(profile, host, port, user, password)
    }.getOrNull()
}
