package dev.aalhdydy550.tunnel.feature.connection

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import dev.aalhdydy550.tunnel.core.data.model.Profile
import dev.aalhdydy550.tunnel.core.data.model.ProfileType
import dev.aalhdydy550.tunnel.core.data.net.DEFAULT_LOCAL_PROXY_PORT
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Proxy-only mode (item 5a): the same sing-box core as
 * [TunnelVpnService], but as a plain foreground service — **not** a
 * `VpnService`. No `BIND_VPN_SERVICE` permission, no
 * `android.net.VpnService` intent-filter, no `VpnService.prepare()`
 * and no system VPN consent dialog. The config it runs has no `tun`
 * inbound (built with `proxyOnly = true`), so the core only serves
 * the local mixed proxy (`127.0.0.1:<port>`); nothing is routed
 * automatically — only apps the user points at the proxy by hand
 * use the tunnel.
 *
 * Known limitations of v1 (see `MODE-STUDY.md` §4):
 * - No automatic reconnect: an engine error surfaces as
 *   [TunnelState.Error] and stops the service; the user reconnects
 *   manually.
 * - No [HeartbeatMonitor] and no [SpeedMonitor]: both measure
 *   through the app's ordinary socket / device-wide `TrafficStats`,
 *   i.e. outside the proxy, so in this mode they would report a
 *   false "connected". They are omitted deliberately, not forgotten.
 * - `SINGBOX_JSON` profiles return their document as-is, so proxy
 *   mode only applies to them if their document already carries a
 *   `mixed` inbound.
 * - No injector path: free-internet injection stays a VPN-mode
 *   feature and always runs through [TunnelVpnService].
 *
 * Lifecycle mirrors [TunnelVpnService]: the last session is saved
 * in [LastSessionStore] (with `isProxy = true`) so a system kill
 * (`START_STICKY`, null intent) revives *this* service, and a
 * deliberate user stop clears the session so a stopped proxy never
 * restarts itself. [TunnelVpnService] refuses proxy sessions on its
 * own revival path, so the two services never fight over the port.
 */
class ProxyOnlyService : Service() {

    companion object {
        private const val TAG = "ProxyOnlyService"

        const val ACTION_START = "dev.aalhdydy550.tunnel.action.PROXY_START"
        const val ACTION_STOP = "dev.aalhdydy550.tunnel.action.PROXY_STOP"

        const val EXTRA_PROXY_PORT = "proxy_port"

        // A different id from TunnelVpnService (1): the two services
        // can never run at once, but their notifications must never
        // overwrite each other either.
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "tunnel"

        fun startIntent(
            context: Context,
            profileId: Long,
            profileName: String,
            profileType: String,
            configJson: String,
            proxyPort: Int = DEFAULT_LOCAL_PROXY_PORT,
        ): Intent = Intent(context, ProxyOnlyService::class.java).apply {
            action = ACTION_START
            putExtra(TunnelVpnService.EXTRA_PROFILE_ID, profileId)
            putExtra(TunnelVpnService.EXTRA_PROFILE_NAME, profileName)
            putExtra(TunnelVpnService.EXTRA_PROFILE_TYPE, profileType)
            putExtra(TunnelVpnService.EXTRA_CONFIG_JSON, configJson)
            putExtra(EXTRA_PROXY_PORT, proxyPort)
        }

        fun stopIntent(context: Context): Intent =
            Intent(context, ProxyOnlyService::class.java).apply {
                action = ACTION_STOP
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()

    private lateinit var engine: TunnelEngine
    private lateinit var lastSessionStore: LastSessionStore

    private var currentProfileId: Long = -1L
    private var currentProfileName: String = ""
    private var currentProfileType: String = ProfileType.VLESS.name
    private var currentConfig: String? = null
    private var currentProxyPort: Int = DEFAULT_LOCAL_PROXY_PORT

    // ------------------------------------------------------------ lifecycle

    override fun onCreate() {
        super.onCreate()
        lastSessionStore = LastSessionStore(this)
        engine = TunnelEngine(
            context = this,
            platformInterface = ProxyPlatformInterface(),
            callbacks = object : TunnelEngine.Callbacks {
                override fun onServiceStopRequested() {
                    // The core asked the service to stop. Stop fully —
                    // do not fight another tunnel by reconnecting.
                    scope.launch {
                        stopProxy("core requested stop", userInitiated = false)
                        stopSelf()
                    }
                }

                override fun onError(message: String) {
                    // v1: no automatic reconnect in proxy mode (see
                    // the class doc). Surface the error and stop; the
                    // user reconnects manually. The Error state is
                    // preserved: onDestroy stops the engine but never
                    // rewrites the state to Stopped.
                    scope.launch {
                        startMutex.withLock { stopEngineSilently() }
                        TunnelEventLog.logDisconnect("proxy engine error: $message")
                        TunnelStateHolder.update(TunnelState.Error(message))
                        stopSelf()
                    }
                }
            },
        )
        createNotificationChannel()
    }

    // No binding: this is a started-only foreground service. The
    // abstract Service.onBind must still be implemented, or the
    // class does not compile (CI caught this on f0b1dcc).
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                scope.launch {
                    stopProxy("user stopped", userInitiated = true)
                    stopSelf()
                }
                return Service.START_NOT_STICKY
            }
            ACTION_START -> {
                val profileId = intent.getLongExtra(
                    TunnelVpnService.EXTRA_PROFILE_ID, -1L,
                )
                val profileName = intent.getStringExtra(
                    TunnelVpnService.EXTRA_PROFILE_NAME,
                ) ?: ""
                val profileType = intent.getStringExtra(
                    TunnelVpnService.EXTRA_PROFILE_TYPE,
                ) ?: ProfileType.VLESS.name
                val configJson = intent.getStringExtra(
                    TunnelVpnService.EXTRA_CONFIG_JSON,
                ) ?: ""
                val proxyPort = intent.getIntExtra(
                    EXTRA_PROXY_PORT, DEFAULT_LOCAL_PROXY_PORT,
                )
                scope.launch {
                    startProxySession(
                        profileId, profileName, profileType, configJson, proxyPort,
                    )
                }
            }
            null -> {
                // START_STICKY restart after the system killed the
                // service: revive the last session, but only if it is
                // a proxy session. A VPN session belongs to
                // TunnelVpnService — reviving it here would start a
                // second engine fighting over the same port.
                val session = lastSessionStore.load()
                if (session == null || !session.isProxy) {
                    if (session != null) {
                        TunnelEventLog.log(
                            "proxy service will not revive a VPN session",
                        )
                    }
                    stopSelf()
                    return Service.START_NOT_STICKY
                }
                TunnelEventLog.log("reviving last proxy session after system kill")
                scope.launch {
                    startProxySession(
                        session.profileId,
                        session.profileName,
                        session.profileType,
                        session.configJson,
                        session.proxyPort,
                    )
                }
            }
        }
        return Service.START_STICKY
    }

    override fun onDestroy() {
        // The engine dies with the service; the saved session is left
        // alone — only an explicit ACTION_STOP clears it, so a system
        // kill can still revive the proxy session. Deliberately no
        // state update here, so an Error set by onError survives.
        if (::engine.isInitialized) runCatching { engine.stop() }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    // ------------------------------------------------------- start / stop

    private suspend fun startProxySession(
        profileId: Long,
        profileName: String,
        profileType: String,
        configJson: String,
        proxyPort: Int,
    ) {
        startMutex.withLock {
            stopEngineSilently()
            currentProfileId = profileId
            currentProfileName = profileName
            currentProfileType = profileType
            currentConfig = configJson
            currentProxyPort = proxyPort
            TunnelStateHolder.update(TunnelState.Starting(profileName))
            updateNotification("جارٍ الاتصال…")
            TunnelEventLog.log("starting proxy: $profileName")
            try {
                // The same placeholder profile TunnelVpnService
                // builds: only id/name/type matter to the engine.
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
                // Never leave a half-started engine behind.
                stopEngineSilently()
                val reason = e.message ?: "start failed"
                TunnelEventLog.logDisconnect(reason)
                TunnelStateHolder.update(TunnelState.Error(reason))
                updateNotification("فشل الاتصال")
                return
            }
            lastSessionStore.save(
                profileId,
                profileName,
                profileType,
                configJson,
                isProxy = true,
                proxyPort = proxyPort,
            )
            TunnelStateHolder.update(TunnelState.Started(profileName))
            startForegroundService()
            TunnelEventLog.log(
                "started proxy: $profileName on 127.0.0.1:$proxyPort",
            )
        }
    }

    /**
     * Stops the proxy. [userInitiated] clears the saved session so a
     * deliberately stopped proxy never restarts itself.
     */
    private suspend fun stopProxy(reason: String, userInitiated: Boolean) {
        startMutex.withLock {
            stopEngineSilently()
            if (userInitiated) {
                lastSessionStore.clear()
            }
            TunnelEventLog.logDisconnect(reason)
            TunnelStateHolder.update(TunnelState.Stopped)
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        }
    }

    /** Stops the engine. Never throws. */
    private fun stopEngineSilently() {
        runCatching { engine.stop() }
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
        val notification = buildNotification(
            "البروكسي يعمل — 127.0.0.1:$currentProxyPort ($currentProfileName)",
        )
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
            2,
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

    /**
     * Minimal [PlatformInterface]: the proxy config carries no `tun`
     * inbound, so libbox never calls [openTun] — it throws if it ever
     * does, instead of silently building a VPN. Everything else is a
     * no-op: there is no VPN interface to protect or monitor here.
     *
     * Technical debt: this duplicates the no-op half of
     * `TunnelVpnService.TunnelPlatformInterface` (the monitor,
     * shell, bridge and DNS-transport methods). Extracting one shared
     * implementation is a later cleanup, not part of this item.
     */
    private inner class ProxyPlatformInterface : PlatformInterface {

        override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

        override fun autoDetectInterfaceControl(fd: Int) {
            // No VPN to bypass: outbound sockets already route
            // normally. Deliberate no-op.
        }

        override fun openTun(options: TunOptions): Int {
            throw UnsupportedOperationException(
                "proxy mode: the config carries a tun inbound " +
                    "(SINGBOX_JSON profiles are not rewritten in " +
                    "proxy mode)",
            )
        }

        override fun findConnectionOwner(
            ipProtocol: Int,
            sourceAddress: String,
            sourcePort: Int,
            destinationAddress: String,
            destinationPort: Int,
        ): ConnectionOwner {
            return ConnectionOwner().also {
                it.userId = Process.INVALID_UID
                it.userName = ""
                it.setAndroidPackageNames(ListStringIterator(emptyList()))
            }
        }

        override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            // Deliberate no-op: no interface monitor in proxy mode
            // (mirrors TunnelVpnService in v0.1). Because the
            // monitor never starts, getInterfaces is never called.
            Log.i(TAG, "startDefaultInterfaceMonitor: no-op in proxy mode")
        }

        override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
            // No-op (paired with startDefaultInterfaceMonitor).
        }

        override fun getInterfaces(): NetworkInterfaceIterator {
            return ListNetworkInterfaceIterator(emptyList())
        }

        override fun underNetworkExtension(): Boolean = false

        override fun includeAllNetworks(): Boolean = false

        override fun clearDNSCache() {
            // sing-box manages its own DNS cache; nothing to clear here.
        }

        override fun readWIFIState(): WIFIState? = null

        override fun localDNSTransport(): LocalDNSTransport? = null

        override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
            // No neighbor-table features in proxy mode.
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
            // No-op in proxy mode.
        }

        override fun sendNotification(notification: io.nekohasekai.libbox.Notification) {
            // No libbox notices are surfaced in proxy mode.
        }

        override fun cancelNotification(identifier: String, typeID: Int) {
            // No-op (paired with sendNotification).
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
}
