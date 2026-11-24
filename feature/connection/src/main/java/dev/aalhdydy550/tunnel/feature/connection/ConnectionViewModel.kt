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
import dev.aalhdydy550.tunnel.core.data.repository.ProxyMode
import dev.aalhdydy550.tunnel.core.data.repository.ProxyModeStore
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
 *
 * Proxy-only mode ([ProxyMode.PROXY], chosen in Settings) is a third
 * route: [connect] skips `VpnService.prepare` for it as well and
 * starts [ProxyOnlyService] instead — the same sing-box engine, but
 * with a config that carries only the `mixed` inbound (no `tun`), so
 * no app's traffic is routed automatically; only apps configured to
 * use the local proxy (127.0.0.1:10808 by default) go through the
 * tunnel. The three routes are decided by the pure function
 * [resolveConnectRoute]. At most one engine runs at a time: every
 * connect path stops the other engines first, and [disconnect]
 * stops both of our services (stopping a service that is not
 * running is a no-op) so no stale "which engine is active" state
 * has to be tracked.
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
    private val proxyModeStore: ProxyModeStore,
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
     * null), asking for VPN permission first — except on the two
     * routes that never need it: OpenVPN profiles (the external app
     * owns the permission) and proxy-only mode (no VPN interface
     * at all). The route is decided by [resolveConnectRoute]. */
    fun connect(profile: Profile? = null) {
        viewModelScope.launch {
            val target = profile ?: selectedProfile.value ?: return@launch
            pendingProfile = target
            when (resolveConnectRoute(target.type, proxyModeStore.mode.first())) {
                ConnectRoute.OPENVPN -> {
                    connectViaOpenVpn(target)
                    return@launch
                }
                ConnectRoute.PROXY -> {
                    connectViaProxy(target)
                    return@launch
                }
                ConnectRoute.VPN -> {
                    // Falls through to the permission check below.
                }
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
            when (resolveConnectRoute(target.type, proxyModeStore.mode.first())) {
                ConnectRoute.OPENVPN -> {
                    // Defensive: the OpenVPN path never asks for our VPN
                    // permission, so reaching here means something routed
                    // it through the permission flow — send it to the
                    // launcher instead of starting our own service.
                    connectViaOpenVpn(target)
                }
                ConnectRoute.PROXY -> {
                    // Defensive: the proxy path never asks for VPN
                    // permission either — reaching here means a wrong
                    // routing, so go to the proxy service instead of
                    // starting the VPN service.
                    connectViaProxy(target)
                }
                ConnectRoute.VPN -> startServiceFor(target)
            }
        }
    }

    /**
     * Stops whatever is running: both of our services are stopped
     * unconditionally (stopping a service that is not running is a
     * no-op — this avoids tracking which engine is active, state
     * that could go stale), and the external OpenVPN app is
     * disconnected when it is connected.
     */
    fun disconnect() {
        if (openVpnConnected) {
            openVpnConnected = false
            openVpnLauncher.disconnect()
        }
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.stopIntent(context),
        )
        ContextCompat.startForegroundService(
            context,
            ProxyOnlyService.stopIntent(context),
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
        // The proxy engine must not keep running either — the
        // external app takes the VPN slot, and two engines must
        // never run together (they would share the local proxy
        // port). Stopping a service that is not running is a no-op.
        ContextCompat.startForegroundService(
            context,
            ProxyOnlyService.stopIntent(context),
        )
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

    /**
     * Proxy-only path: no `VpnService.prepare()` is ever called here
     * — there is no VPN interface to prepare. Our VPN engine is
     * stopped first and the external OpenVPN app disconnected, then
     * the same config is built with `proxyOnly = true` (only the
     * `mixed` inbound remains) and [ProxyOnlyService] is started
     * with the effective local proxy port.
     */
    private suspend fun connectViaProxy(target: Profile) {
        ContextCompat.startForegroundService(
            context,
            TunnelVpnService.stopIntent(context),
        )
        if (openVpnConnected) {
            openVpnLauncher.disconnect()
            openVpnConnected = false
        }
        val config = buildConfigFor(target, proxyOnly = true)
        val localProxy = localProxyStore.settings.first()
        ContextCompat.startForegroundService(
            context,
            ProxyOnlyService.startIntent(
                context = context,
                profileId = target.id,
                profileName = target.name,
                profileType = target.type.name,
                configJson = config,
                proxyPort = SingBoxConfigBuilder.effectiveLocalProxyPort(localProxy.port),
            ),
        )
        pendingProfile = target
    }

    /**
     * Builds the sing-box document for [profile] from the stored
     * settings, shared by the VPN path ([startServiceFor]) and the
     * proxy path ([connectViaProxy]) so the two never drift apart.
     * With [proxyOnly] the builder drops the `tun` inbound and
     * keeps only `mixed` — the per-app lists live inside the `tun`
     * inbound, so they are dropped with it (proxy mode has no
     * per-app routing).
     */
    private suspend fun buildConfigFor(profile: Profile, proxyOnly: Boolean): String {
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
        return SingBoxConfigBuilder.buildConfig(
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
            proxyOnly = proxyOnly,
        )
    }

    private suspend fun startServiceFor(profile: Profile, proxyOnly: Boolean = false) {
        // Switching away from OpenVPN: stop the external app first —
        // two VPN services cannot run together. Done here, after the
        // VPN permission is granted, so a denied permission leaves
        // the external tunnel running instead of leaving the user
        // with no VPN at all.
        if (openVpnConnected) {
            openVpnLauncher.disconnect()
            openVpnConnected = false
        }
        val config = buildConfigFor(profile, proxyOnly = proxyOnly)
        // The proxy engine must not keep running next to the VPN
        // engine — they would share the local proxy port. Stopping
        // a service that is not running is a no-op, and it is done
        // here, just before the VPN service starts, so a config
        // failure above leaves a running proxy untouched.
        ContextCompat.startForegroundService(
            context,
            ProxyOnlyService.stopIntent(context),
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
