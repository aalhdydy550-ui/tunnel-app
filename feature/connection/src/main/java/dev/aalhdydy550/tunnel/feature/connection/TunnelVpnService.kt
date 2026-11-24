package dev.aalhdydy550.tunnel.feature.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.Network
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.net.ReconnectPolicy
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefixIterator
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetAddress
import java.net.NetworkInterface as JavaNetworkInterface

/**
 * The VPN service: one foreground service, one TUN interface, one
 * sing-box core. This is the stability heart of the app:
 *
 * - Foreground service with a permanent notification, so Android does
 *   not kill the tunnel (and START_STICKY so the system revives it).
 * - Automatic reconnect with backoff on network change (WiFi ↔ mobile
 *   handover), on tunnel failure, and on silent death caught by the
 *   heartbeat — the UI always shows `جارٍ إعادة الاتصال…` with the
 *   attempt number.
 * - Clean lifecycle: the TUN file descriptor is closed before every
 *   re-establish and on every stop; every disconnection logs its
 *   reason ([TunnelEventLog]).
 * - Revival: the last session is persisted ([LastSessionStore]) so a
 *   system kill (null intent) or a reboot (with auto-start on boot)
 *   restores the tunnel without the user touching anything. A
 *   deliberate user stop clears the session, so a stopped tunnel never
 *   restarts itself.
 *
 * The [PlatformInterface] implementation follows the pattern verified
 * against the libbox 1.14.4 bindings shipped in sing-box-for-android
 * (dexdump of the official SFA 1.14.4 APK + SFA's own wrapper, studied
 * for the API contract, not copied). Two deliberate simplifications
 * for v0.1, both documented:
 * - `startDefaultInterfaceMonitor` is a no-op: the service-level
 *   [ConnectivityManager.NetworkCallback] below + a full engine
 *   restart already re-establishes the tunnel on the new network, so
 *   libbox's own interface monitor adds nothing in v0.1. Wiring it
 *   (calling `listener.updateDefaultInterface`) is a Phase 2
 *   refinement once the exact listener contract is confirmed.
 * - No shell / bridge / neighbor monitor / local DNS transport: the
 *   app has no root features and sing-box handles DNS itself.
 *
 * Runs in the main process (no `android:process`) — one process total,
 * unlike the original's separate V2Ray daemon process + external
 * tun2socks/pdnsd/Hysteria processes.
 */
class TunnelVpnService : VpnService() {

    companion object {
        private const val TAG = "TunnelVpnService"

        const val ACTION_START = "dev.aalhdydy550.tunnel.action.START"
        const val ACTION_STOP = "dev.aalhdydy550.tunnel.action.STOP"

        const val EXTRA_PROFILE_ID = "profile_id"
        const val EXTRA_PROFILE_NAME = "profile_name"
        const val EXTRA_PROFILE_TYPE = "profile_type"
        const val EXTRA_CONFIG_JSON = "config_json"

        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "tunnel"

        fun startIntent(
            context: Context,
            profileId: Long,
            profileName: String,
            profileType: String,
            configJson: String,
        ): Intent = Intent(context, TunnelVpnService::class.java).apply {
            action = ACTION_START
            putExtra(EXTRA_PROFILE_ID, profileId)
            putExtra(EXTRA_PROFILE_NAME, profileName)
            putExtra(EXTRA_PROFILE_TYPE, profileType)
            putExtra(EXTRA_CONFIG_JSON, configJson)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, TunnelVpnService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    private lateinit var engine: TunnelEngine
    private lateinit var platformInterface: TunnelPlatformInterface
    private lateinit var heartbeat: HeartbeatMonitor
    private lateinit var speedMonitor: SpeedMonitor
    private lateinit var lastSessionStore: LastSessionStore

    private var tunFd: ParcelFileDescriptor? = null
    private var currentProfileId: Long = -1L
    private var currentProfileName: String = ""
    private var currentProfileType: String = ProfileType.VLESS.name
    private var currentConfig: String? = null

    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var userStopped = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null
    private var lastReconnectTriggerMs: Long = 0L

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        lastSessionStore = LastSessionStore(this)
        platformInterface = TunnelPlatformInterface()
        engine = TunnelEngine(
            context = this,
            platformInterface = platformInterface,
            callbacks = object : TunnelEngine.Callbacks {
                override fun onServiceStopRequested() {
                    // The core asked the service to stop (for example the
                    // system took the VPN slot). Stop fully — do not
                    // fight another VPN by reconnecting.
                    scope.launch {
                        stopTunnel("core requested stop", userInitiated = false)
                        stopSelf()
                    }
                }

                override fun onError(message: String) {
                    scope.launch { scheduleReconnect("engine error: $message") }
                }
            },
        )
        heartbeat = HeartbeatMonitor(
            scope = scope,
            onUnhealthy = { reason ->
                scope.launch { onTunnelUnhealthy(reason) }
            },
        )
        speedMonitor = SpeedMonitor(scope)
        scope.launch {
            speedMonitor.speed.collect { TunnelSpeedHolder.update(it) }
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    stopTunnel("user stopped", userInitiated = true)
                    stopSelf()
                }
                return Service.START_NOT_STICKY
            }
            ACTION_START -> {
                userStopped = false
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1L)
                val profileName = intent.getStringExtra(EXTRA_PROFILE_NAME) ?: ""
                val profileType = intent.getStringExtra(EXTRA_PROFILE_TYPE)
                    ?: ProfileType.VLESS.name
                val configJson = intent.getStringExtra(EXTRA_CONFIG_JSON) ?: ""
                scope.launch {
                    startTunnel(profileId, profileName, profileType, configJson)
                }
            }
            null -> {
                // START_STICKY restart after the system killed the
                // service: revive the last session, if there is one.
                val session = lastSessionStore.load()
                if (session != null) {
                    TunnelEventLog.log("reviving last session after system kill")
                    scope.launch {
                        startTunnel(
                            session.profileId,
                            session.profileName,
                            session.profileType,
                            session.configJson,
                        )
                    }
                } else {
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        scope.launch {
            stopTunnel("service destroyed", userInitiated = false)
        }
        unregisterNetworkCallback()
        super.onDestroy()
    }

    override fun onRevoke() {
        // The system revoked the VPN permission (another VPN, or the
        // user removed it in Settings). Stop and clear the session.
        scope.launch {
            stopTunnel("VPN permission revoked", userInitiated = false)
            lastSessionStore.clear()
            TunnelStateHolder.update(TunnelState.Error("تم إلغاء إذن الـVPN"))
            stopSelf()
        }
        super.onRevoke()
    }

    // ------------------------------------------------------- start / stop

    private suspend fun startTunnel(
        profileId: Long,
        profileName: String,
        profileType: String,
        configJson: String,
    ) {
        startMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            stopEngineSilently()
            currentProfileId = profileId
            currentProfileName = profileName
            currentProfileType = profileType
            currentConfig = configJson
            TunnelStateHolder.update(TunnelState.Starting(profileName))
            updateNotification("جارٍ الاتصال…")
            TunnelEventLog.log("starting: $profileName")
            try {
                val profile = Profile(
                    id = profileId,
                    name = profileName,
                    type = ProfileType.valueOf(profileType),
                    server = "",
                    port = 0,
                    outboundJson = "",
                )
                engine.start(profile, configJson)
            } catch (e: Exception) {
                val reason = e.message ?: "start failed"
                TunnelEventLog.logDisconnect(reason)
                TunnelStateHolder.update(TunnelState.Error(reason))
                updateNotification("فشل الاتصال")
                return
            }
            lastSessionStore.save(profileId, profileName, profileType, configJson)
            TunnelStateHolder.update(TunnelState.Started(profileName))
            startForegroundService()
            updateNotification("متصل — $profileName")
            registerNetworkCallback()
            heartbeat.start()
            speedMonitor.start()
            TunnelEventLog.log("started: $profileName")
        }
    }

    /**
     * Stops the tunnel. [userInitiated] clears the saved session so a
     * deliberately stopped tunnel never restarts itself.
     */
    private suspend fun stopTunnel(reason: String, userInitiated: Boolean) {
        startMutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            reconnectAttempt = 0
            heartbeat.stop()
            speedMonitor.stop()
            stopEngineSilently()
            if (userInitiated) {
                lastSessionStore.clear()
                userStopped = true
            }
            TunnelEventLog.logDisconnect(reason)
            TunnelStateHolder.update(TunnelState.Stopped)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    /** Stops the engine + closes the TUN fd. Never throws. */
    private fun stopEngineSilently() {
        runCatching { engine.stop() }
        closeTunFd()
    }

    private fun closeTunFd() {
        runCatching { tunFd?.close() }
        tunFd = null
    }

    // ---------------------------------------------------------- reconnect

    private suspend fun scheduleReconnect(reason: String) {
        if (userStopped) return
        if (currentConfig == null) return
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            var attempt = reconnectAttempt + 1
            while (true) {
                reconnectAttempt = attempt
                val delayMs = ReconnectPolicy.delayForAttempt(attempt)
                TunnelStateHolder.update(
                    TunnelState.Reconnecting(currentProfileName, attempt, reason),
                )
                updateNotification("جارٍ إعادة الاتصال… (المحاولة $attempt)")
                TunnelEventLog.log(
                    "reconnect attempt $attempt in ${delayMs}ms (reason: $reason)",
                )
                delay(delayMs)
                val ok = try {
                    stopEngineSilently()
                    val profile = Profile(
                        id = currentProfileId,
                        name = currentProfileName,
                        type = ProfileType.valueOf(currentProfileType),
                        server = "",
                        port = 0,
                        outboundJson = "",
                    )
                    engine.start(profile, currentConfig!!)
                    true
                } catch (e: Exception) {
                    TunnelEventLog.logDisconnect(
                        "reconnect attempt $attempt failed: ${e.message}",
                    )
                    false
                }
                if (ok) {
                    reconnectAttempt = 0
                    reconnectJob = null
                    TunnelStateHolder.update(TunnelState.Started(currentProfileName))
                    updateNotification("متصل — $currentProfileName")
                    heartbeat.start()
                    speedMonitor.start()
                    TunnelEventLog.log("reconnected: $currentProfileName")
                    return@launch
                }
                attempt++
            }
        }
    }

    private fun onTunnelUnhealthy(reason: String) {
        scope.launch {
            TunnelEventLog.log("heartbeat: tunnel looks dead ($reason)")
            scheduleReconnect(reason)
        }
    }

    // ----------------------------------------------------- network changes

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val connectivity =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = lastNetwork
                lastNetwork = network
                // The first callback reports the network we already run
                // on — only a *change* (WiFi ↔ mobile) triggers a
                // reconnect, debounced against flapping.
                if (previous != null && previous != network) {
                    val now = System.currentTimeMillis()
                    if (now - lastReconnectTriggerMs > 1_500) {
                        lastReconnectTriggerMs = now
                        TunnelEventLog.log("network changed (WiFi ↔ mobile)")
                        scope.launch {
                            scheduleReconnect("network changed")
                        }
                    }
                }
            }

            override fun onLost(network: Network) {
                // The heartbeat catches a fully dead network; the
                // onAvailable of the replacement network triggers the
                // reconnect.
            }
        }
        networkCallback = callback
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        lastNetwork = null
        val connectivity =
            getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    // ------------------------------------------------------- notification

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Ayman Tunnel Plus",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "حالة اتصال النفق"
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun startForegroundService() {
        val notification = buildNotification("متصل — $currentProfileName")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching {
            manager.notify(NOTIFICATION_ID, buildNotification(statusText))
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val openApp = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val disconnect = PendingIntent.getService(
            this,
            1,
            stopIntent(this),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Ayman Tunnel Plus")
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .addAction(0, "قطع الاتصال", disconnect)
            .build()
    }

    // -------------------------------------------- PlatformInterface (libbox)

    private inner class TunnelPlatformInterface : PlatformInterface {

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            // Let sing-box's outbound sockets bypass the VPN, or they
            // would route through the tunnel itself.
            protect(fd)
        }

        override fun openTun(options: TunOptions): Int {
            if (prepare(this@TunnelVpnService) != null) {
                error("android: missing vpn permission")
            }
            // Never leak the previous fd: close before re-establishing.
            closeTunFd()
            val builder = Builder()
                .setSession("Ayman Tunnel Plus")
                .setMtu(options.mtu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            val inet4Address = options.inet4Address
            while (inet4Address.hasNext()) {
                val address = inet4Address.next()
                builder.addAddress(address.address(), address.prefix())
            }
            val inet6Address = options.inet6Address
            while (inet6Address.hasNext()) {
                val address = inet6Address.next()
                builder.addAddress(address.address(), address.prefix())
            }
            if (options.autoRoute) {
                if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                    val dnsServers = options.dnsServerAddress
                    while (dnsServers.hasNext()) {
                        builder.addDnsServer(dnsServers.next())
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val inet4Routes = options.inet4RouteAddress
                    if (inet4Routes.hasNext()) {
                        while (inet4Routes.hasNext()) {
                            val route = inet4Routes.next()
                            builder.addRoute(route.address(), route.prefix())
                        }
                    } else if (options.inet4Address.hasNext()) {
                        builder.addRoute("0.0.0.0", 0)
                    }
                    val inet6Routes = options.inet6RouteAddress
                    if (inet6Routes.hasNext()) {
                        while (inet6Routes.hasNext()) {
                            val route = inet6Routes.next()
                            builder.addRoute(route.address(), route.prefix())
                        }
                    } else if (options.inet6Address.hasNext()) {
                        builder.addRoute("::", 0)
                    }
                    val inet4Excludes = options.inet4RouteExcludeAddress
                    while (inet4Excludes.hasNext()) {
                        val route = inet4Excludes.next()
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(route.address()), route.prefix()))
                    }
                    val inet6Excludes = options.inet6RouteExcludeAddress
                    while (inet6Excludes.hasNext()) {
                        val route = inet6Excludes.next()
                        builder.excludeRoute(IpPrefix(InetAddress.getByName(route.address()), route.prefix()))
                    }
                } else {
                    // Before Android 13 the Builder has no excludeRoute;
                    // the global 0.0.0.0/0 + ::/0 routes still apply.
                    builder.addRoute("0.0.0.0", 0)
                    builder.addRoute("::", 0)
                }
            }
            val includePackages = options.includePackage
            while (includePackages.hasNext()) {
                runCatching { builder.addAllowedApplication(includePackages.next()) }
            }
            val excludePackages = options.excludePackage
            while (excludePackages.hasNext()) {
                runCatching { builder.addDisallowedApplication(excludePackages.next()) }
            }
            if (options.isHTTPProxyEnabled()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val bypass = mutableListOf<String>()
                    val bypassIterator = options.httpProxyBypassDomain
                    while (bypassIterator.hasNext()) bypass += bypassIterator.next()
                    val match = mutableListOf<String>()
                    val matchIterator = options.httpProxyMatchDomain
                    while (matchIterator.hasNext()) match += matchIterator.next()
                    builder.setHttpProxy(
                        ProxyInfo.buildDirectProxy(
                            options.httpProxyServer,
                            options.httpProxyServerPort,
                            bypass + match,
                        ),
                    )
                } else {
                    Log.w(TAG, "HTTP proxy requested but unsupported below Android 10")
                }
            }
            val fd = builder.establish()
                ?: error("android: failed to establish VPN interface")
            tunFd = fd
            return fd.detachFd()
        }

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): ConnectionOwner {
            val connectivity =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val uid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                runCatching {
                    connectivity.getConnectionOwnerUid(
                        ipProtocol,
                        java.net.InetSocketAddress(sourceAddress, sourcePort),
                        java.net.InetSocketAddress(destinationAddress, destinationPort),
                    )
                }.getOrDefault(Process.INVALID_UID)
            } else {
                Process.INVALID_UID
            }
            val owner = if (uid != Process.INVALID_UID) {
                ConnectionOwner().also { it.userId = uid }
            } else {
                Libbox.findConnectionOwner(
                    ipProtocol,
                    sourceAddress,
                    sourcePort,
                    destinationAddress,
                    destinationPort,
                )
            }
            val packages = packageManager.getPackagesForUid(owner.userId)
            owner.userName = packages?.firstOrNull() ?: ""
            owner.setAndroidPackageNames(
                ListStringIterator(packages?.toList() ?: emptyList()),
            )
            return owner
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            // Deliberate no-op in v0.1 — see the class doc.
            Log.i(TAG, "startDefaultInterfaceMonitor: no-op in v0.1")
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            // No-op (paired with startDefaultInterfaceMonitor).
        }

        override fun getInterfaces(): NetworkInterfaceIterator {
            val connectivity =
                getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val interfaces = mutableListOf<LibboxNetworkInterface>()
            val javaInterfaces = JavaNetworkInterface.getNetworkInterfaces()?.toList()
                ?: emptyList()
            for (network in connectivity.allNetworks) {
                val linkProperties = connectivity.getLinkProperties(network) ?: continue
                val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
                val name = linkProperties.interfaceName ?: continue
                val javaInterface = javaInterfaces.find { it.name == name } ?: continue
                val boxInterface = LibboxNetworkInterface().apply {
                    this.name = name
                    dnsServer = ListStringIterator(
                        linkProperties.dnsServers.mapNotNull { it.hostAddress },
                    )
                    gateway = ListStringIterator(
                        linkProperties.routes
                            .filter { it.destination?.prefixLength == 0 }
                            .mapNotNull { it.gateway }
                            .filterNot { it.isAnyLocalAddress }
                            .mapNotNull { it.hostAddress },
                    )
                    type = when {
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ->
                            Libbox.InterfaceTypeWIFI
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                            Libbox.InterfaceTypeCellular
                        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ->
                            Libbox.InterfaceTypeEthernet
                        else -> Libbox.InterfaceTypeOther
                    }
                    index = javaInterface.index
                    runCatching { mtu = javaInterface.mtu }
                    addresses = ListStringIterator(
                        javaInterface.interfaceAddresses.map {
                            "${it.address.hostAddress}/${it.networkPrefixLength}"
                        },
                    )
                    var dumpFlags = 0
                    if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                        dumpFlags = dumpFlags or android.system.OsConstants.IFF_UP or
                            android.system.OsConstants.IFF_RUNNING
                    }
                    if (javaInterface.isLoopback) {
                        dumpFlags = dumpFlags or android.system.OsConstants.IFF_LOOPBACK
                    }
                    if (javaInterface.isPointToPoint) {
                        dumpFlags = dumpFlags or android.system.OsConstants.IFF_POINTOPOINT
                    }
                    if (javaInterface.supportsMulticast()) {
                        dumpFlags = dumpFlags or android.system.OsConstants.IFF_MULTICAST
                    }
                    flags = dumpFlags
                    metered = !capabilities.hasCapability(
                        NetworkCapabilities.NET_CAPABILITY_NOT_METERED,
                    )
                }
                interfaces += boxInterface
            }
            return ListNetworkInterfaceIterator(interfaces)
        }

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun clearDNSCache() {
            // sing-box manages its own DNS cache; nothing to clear here.
        }

        @Suppress("DEPRECATION")
        override fun readWIFIState(): WIFIState? {
            val wifi = applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null
            val info = wifi.connectionInfo ?: return null
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?: return null
            val bssid = info.bssid ?: ""
            return Libbox.newWIFIState(ssid, bssid)
        }

        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
            // No neighbor-table features in v0.1.
        }

        override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
            // No-op (paired with startNeighborMonitor).
        }

        override fun usePlatformShell(): Boolean = false

        override fun checkPlatformShell() {
            throw UnsupportedOperationException("platform shell is not supported")
        }

        override fun openShellSession(
            user: PlatformUser?,
            name: String?,
            arguments: StringIterator?,
            environment: String?,
            rows: Int,
            cols: Int,
        ): ShellSession {
            throw UnsupportedOperationException("platform shell is not supported")
        }

        override fun readSystemSSHHostKey(): String = ""

        override fun lookupSFTPServer(): String = ""

        override fun tailscaleHostname(): String = ""

        override fun usePlatformBridge(): Boolean = false

        override fun createBridge(options: BridgeOptions?): BridgeSession {
            throw UnsupportedOperationException("platform bridge is not supported")
        }

        override fun lookupUser(username: String?): PlatformUser {
            throw UnsupportedOperationException("user lookup is not supported")
        }

        override fun registerMyInterface(name: String?) {
            // No-op in v0.1.
        }

        override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
            // sing-box informational notices (for example profile
            // updates): surface them on the tunnel channel.
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val built = NotificationCompat.Builder(this@TunnelVpnService, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .build()
            runCatching { manager.notify(notification.typeID, built) }
        }

        override fun cancelNotification(identifier: String, typeID: Int) {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            runCatching { manager.cancel(typeID) }
        }
    }

    private class ListStringIterator(
        private val items: List<String>,
    ) : StringIterator {
        private val iterator = items.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
        override fun len(): Int = items.size
    }

    private class ListNetworkInterfaceIterator(
        private val items: List<LibboxNetworkInterface>,
    ) : NetworkInterfaceIterator {
        private val iterator = items.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    @Suppress("unused")
    private class ListRoutePrefixIterator(
        private val items: List<io.nekohasekai.libbox.RoutePrefix>,
    ) : RoutePrefixIterator {
        private val iterator = items.iterator()
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): io.nekohasekai.libbox.RoutePrefix = iterator.next()
    }
}
