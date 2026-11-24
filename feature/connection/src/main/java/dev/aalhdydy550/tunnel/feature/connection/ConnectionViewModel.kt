package dev.aalhdydy550.tunnel.feature.connection

import android.content.Context
import android.net.VpnService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.net.DnsSelector
import dev.aalhdydy550.tunnel.core.data.net.EndpointProbe
import dev.aalhdydy550.tunnel.core.data.repository.AllowInsecureStore
import dev.aalhdydy550.tunnel.core.data.repository.DnsPreferenceStore
import dev.aalhdydy550.tunnel.core.data.repository.LocalProxyStore
import dev.aalhdydy550.tunnel.core.data.repository.PerAppProxyStore
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.data.repository.SettingsRepository
import dev.aalhdydy550.tunnel.core.data.routing.RoutingDataManager
import dev.aalhdydy550.tunnel.core.data.singbox.SingBoxConfigBuilder
import dev.aalhdydy550.tunnel.feature.connection.openvpn.OpenVpnLauncher
import dev.aalhdydy550.tunnel.feature.connection.openvpn.OpenVpnResult
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
 * and the connect/disconnect actions. The live speed is shown on the
 * main screen at all times (no separate speed-test button).
 *
 * Connect flow: [connect] takes the profile to use (the connect
 * button passes the fastest server it just picked, or null to use the
 * selected one); if `VpnService.prepare()` returns null the permission
 * is already granted and the service starts directly; otherwise the
 * ViewModel emits [vpnPermissionRequest] and the UI launches the
 * system permission screen, then calls [onVpnPermissionGranted] — which
 * reconnects to the same profile [connect] was given.
 *
 * OpenVPN profiles ([ProfileType.OPENVPN]) never take that path: they
 * are connected through the separate OpenVPN for Android app
 * ([OpenVpnLauncher]), which owns the VPN permission itself, so
 * [connect] skips `VpnService.prepare` for them entirely and stops
 * our own engine first. Results the user must see (app not
 * installed, invalid config, failure) arrive on [openVpnMessage].
 */
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileRepository: ProfileRepository,
    private val settingsRepository: SettingsRepository,
    private val perAppProxyStore: PerAppProxyStore,
    private val dnsPreferenceStore: DnsPreferenceStore,
    private val localProxyStore: LocalProxyStore,
    private val allowInsecureStore: AllowInsecureStore,
    private val openVpnLauncher: OpenVpnLauncher,
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

    /**
     * OpenVPN connect results the UI must show: app not installed
     * (with the Play Store link), an invalid stored `.ovpn`, or a
     * launcher failure — each as the Arabic message to display.
     * A successful start emits nothing (the external app shows its
     * own notification). Collected by ServersScreen, which shows
     * the message as a dismissible notice under the connection
     * panel.
     */
    private val _openVpnMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openVpnMessage: SharedFlow<String> = _openVpnMessage.asSharedFlow()

    /** True while an OpenVPN profile is connected through the external
     * app (last known state only — the launcher has no synchronous
     * status read; see `OpenVpnLauncher.isActive`). Set in
     * [connect], cleared in [disconnect]. */
    private var openVpnConnected = false

    /** The profile the last [connect] call was given, so
     * [onVpnPermissionGranted] reconnects to the same server. */
    private var pendingProfile: Profile? = null

    private val ruleSetsDir: File
        get() = File(context.filesDir, RoutingDataManager.RULE_SETS_DIR_NAME)

    /** Starts the tunnel for [profile] (or the selected profile when
     * null), asking for VPN permission first. OpenVPN profiles skip
     * our permission flow entirely — see [connectViaOpenVpn]. */
    fun connect(profile: Profile? = null) {
        viewModelScope.launch {
            val target = profile ?: selectedProfile.value ?: return@launch
            pendingProfile = target
            if (target.type == ProfileType.OPENVPN) {
                connectViaOpenVpn(target)
                return@launch
            }
            if (VpnService.prepare(context) != null) {
                _vpnPermissionRequest.emit(Unit)
                return@launch
            }
            startServiceFor(target)
        }
    }

    /** Called by the UI after the user granted the VPN permission. */
    fun onVpnPermissionGranted() {
        viewModelScope.launch {
            val target = pendingProfile ?: selectedProfile.value ?: return@launch
            if (target.type == ProfileType.OPENVPN) {
                // Defensive: the OpenVPN path never asks for our VPN
                // permission, so reaching here means something routed
                // it through the permission flow — send it to the
                // launcher instead of starting our own service.
                connectViaOpenVpn(target)
                return@launch
            }
            startServiceFor(target)
        }
    }

    fun disconnect() {
        if (openVpnConnected) {
            openVpnConnected = false
            openVpnLauncher.disconnect()
            return
        }
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.stopIntent(context),
        )
    }

    /**
     * OpenVPN path: `VpnService.prepare` is skipped entirely — the
     * external app owns the VPN permission, not us. Our own engine
     * is stopped first when it is running (two VPN services cannot
     * run together); a `Started` result marks the external
     * connection active, anything else is reported on
     * [openVpnMessage] for the UI to show.
     */
    private suspend fun connectViaOpenVpn(target: Profile) {
        if (state.value != TunnelState.Stopped) {
            ContextCompat.startForegroundService(
                context,
                TunnelVpnService.stopIntent(context),
            )
        }
        when (val result = openVpnLauncher.connect(target)) {
            OpenVpnResult.Started -> {
                openVpnConnected = true
            }
            OpenVpnResult.NotInstalled -> {
                openVpnConnected = false
                _openVpnMessage.emit(
                    "تطبيق OpenVPN for Android غير مثبَّت — ثبّته من متجر Play أولًا: " +
                        openVpnLauncher.playStoreUrl(),
                )
            }
            is OpenVpnResult.InvalidConfig -> {
                openVpnConnected = false
                _openVpnMessage.emit(result.message)
            }
            is OpenVpnResult.Failed -> {
                openVpnConnected = false
                _openVpnMessage.emit(result.message)
            }
        }
    }

    private suspend fun startServiceFor(profile: Profile) {
        // Switching away from OpenVPN: stop the external app first —
        // two VPN services cannot run together. Done here, after the
        // VPN permission is granted, so a denied permission leaves
        // the external tunnel running instead of leaving the user
        // with no VPN at all.
        if (openVpnConnected) {
            openVpnLauncher.disconnect()
            openVpnConnected = false
        }
        // DNS: a pinned choice from the DNS screen wins; AUTO (the
        // default) keeps the previous behaviour — fastest public
        // resolver at connect time (best-effort, ~1.5s budget), falling
        // back to tls://1.1.1.1 when none answers. A CUSTOM choice
        // whose host fails validation also resolves to null here, so
        // it falls back to the probe instead of a broken address.
        val dnsPreference = dnsPreferenceStore.preference.first()
        val dnsServer = dnsPreference.dotAddress
            ?: withContext(Dispatchers.IO) {
                DnsSelector.selectFastest()?.dotAddress
            }
            ?: SingBoxConfigBuilder.DEFAULT_DNS_SERVER
        // Per-app proxy: the stored choice, or empty lists (every app
        // through the tunnel) when the mode is ALL — the default.
        val perAppProxy = perAppProxyStore.settings.first()
        // Local proxy + allow-insecure: the stored settings, passed to
        // the builder so it can expose the local mixed inbound and
        // relax TLS verification. They only affect generated configs —
        // SINGBOX_JSON profiles return their stored document unchanged,
        // so these settings are ignored for them (the same limitation
        // as autoRoute/adsBlocking today).
        val localProxy = localProxyStore.settings.first()
        val allowInsecure = allowInsecureStore.enabled.first()
        val config = SingBoxConfigBuilder.buildConfig(
            profile = profile,
            autoRoute = settingsRepository.autoRoute.first(),
            strictRoute = settingsRepository.strictRoute.first(),
            adsBlocking = settingsRepository.adsBlocking.first(),
            ruleSetsDir = ruleSetsDir.absolutePath,
            dnsServer = dnsServer,
            includePackages = perAppProxy.includePackages(),
            excludePackages = perAppProxy.excludePackages(),
            localProxyPort = localProxy.port,
            allowOtherDevices = localProxy.allowOtherDevices,
            allowInsecure = allowInsecure,
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
