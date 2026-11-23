package dev.aalhdydy550.tunnel.feature.connection

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import dev.aalhdydy550.tunnel.core.data.model.Backend
import dev.aalhdydy550.tunnel.core.data.model.Profile
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.SetupOptions
import io.nekohasekai.libbox.SystemProxyStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Owns the libbox lifecycle for one VPN session.
 *
 * Order of operations (also the Phase 2 order — see `docs/PHASE2.md`):
 * 1. `Libbox.setup` once per process.
 * 2. `Libbox.checkConfig` validates the configuration document.
 * 3. If the profile needs an external-process backend, start it first
 *    (v0.1: throws with a clear Phase 2 message).
 * 4. Start the [SingBoxBackend] (CommandServer).
 * Stop runs the same steps in reverse.
 *
 * Implements [CommandServerHandler]: libbox calls back into [serviceStop]
 * / [serviceReload] from its own threads, so those delegate to the
 * [Callbacks] on the engine scope.
 *
 * API verified against the libbox 1.14.4 bindings shipped in
 * sing-box-for-android 1.14.4 (dexdump of the official SFA APK).
 */
class TunnelEngine(
    private val context: Context,
    private val platformInterface: PlatformInterface,
    private val callbacks: Callbacks,
) : CommandServerHandler {

    interface Callbacks {
        fun onServiceStopRequested()
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "TunnelEngine"
        private const val APP_VERSION = "0.1.0"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var setupDone = false
    private var backend: TunnelBackend? = null
    private var currentConfig: String? = null
    private var currentProfileName: String = ""

    /** Idempotent: safe to call before every start. */
    fun setupOnce() {
        if (setupDone) return
        val options = SetupOptions()
        options.basePath = context.filesDir.absolutePath
        options.workingPath = File(context.filesDir, "libbox").absolutePath
        options.tempPath = context.cacheDir.absolutePath
        options.appVersion = APP_VERSION
        options.appMarketingVersion = APP_VERSION
        options.crashReportSource = "tunnel"
        // Footprint: bound the in-core sing-box log buffer (the log is
        // mirrored to Logcat only; no log file is written on the phone).
        options.logMaxLines = 1000
        options.debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        Libbox.setup(options)
        Libbox.promoteOOMDraft()
        setupDone = true
        Log.i(TAG, "libbox ${Libbox.version()} set up")
    }

    /** Starts the tunnel for [profile] with the given sing-box [configJson]. */
    fun start(profile: Profile, configJson: String) {
        setupOnce()
        Libbox.checkConfig(configJson)
        currentConfig = configJson
        currentProfileName = profile.name
        if (profile.backend == Backend.EXTERNAL_PROCESS) {
            // Phase 2: the helper process must listen on its local SOCKS5
            // before sing-box starts (the config already points at it).
            startExternalProcess(profile)
        }
        val singBox = SingBoxBackend(this, platformInterface)
        singBox.start(configJson)
        backend = singBox
        Log.i(TAG, "started: ${profile.name}")
    }

    /** Stops the tunnel. Never throws. */
    fun stop() {
        runCatching { backend?.stop() }
        backend = null
        stopExternalProcess()
        currentConfig = null
        runCatching { Libbox.discardPowerReportDraft() }
    }

    /**
     * Phase 2 hook. A real implementation launches the native helper
     * (for example `udp-zivpn` or `dnstt-client`) as a child process and
     * blocks until its local SOCKS5 endpoint answers. v0.1 deliberately
     * throws: shipping a stub that pretends to connect would be worse
     * than a clear error.
     */
    private fun startExternalProcess(profile: Profile): Nothing {
        throw UnsupportedOperationException(
            "External-process backends (${profile.type}) are planned for " +
                "Phase 2 — see docs/PHASE2.md",
        )
    }

    private fun stopExternalProcess() {
        // Nothing is started in v0.1.
    }

    // ------------------------------------------------------- CommandServerHandler

    override fun serviceStop() {
        callbacks.onServiceStopRequested()
    }

    override fun serviceReload() {
        scope.launch {
            val config = currentConfig ?: return@launch
            runCatching {
                (backend as? SingBoxBackend)?.reload(config)
            }.onFailure {
                callbacks.onError(it.message ?: "reload failed")
            }
        }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus {
        // v0.1 does not manage the system proxy.
        return SystemProxyStatus().apply {
            available = false
            enabled = false
        }
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
        // v0.1 does not manage the system proxy.
    }

    override fun triggerNativeCrash() {
        Thread {
            Thread.sleep(200)
            throw RuntimeException("debug native crash")
        }.start()
    }

    override fun writeDebugMessage(message: String?) {
        Log.d("sing-box", message ?: "")
    }

    override fun connectSSHAgent(): Int = -1
}
