package dev.aalhdydy550.tunnel.feature.connection

import dev.aalhdydy550.tunnel.core.data.model.Backend
import dev.aalhdydy550.tunnel.core.data.model.Profile
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface

/**
 * A tunnel backend: something that carries traffic for one sing-box
 * configuration document.
 *
 * v0.1 ships one implementation ([SingBoxBackend]). Phase 2 adds
 * [ExternalProcessBackend]-style backends (see `docs/PHASE2.md`): a native
 * helper process exposing a local SOCKS5, started *before* the sing-box
 * backend and stopped *after* it. The engine ([TunnelEngine]) already
 * orders the two phases; a new backend only needs to implement this
 * interface and be selected by [Profile.backend].
 */
interface TunnelBackend {
    /** Starts the backend for [configJson]. Throws on failure. */
    fun start(configJson: String)

    /** Stops the backend. Must not throw. */
    fun stop()
}

/**
 * The sing-box backend: a libbox [CommandServer] driven by the given
 * [CommandServerHandler] and [PlatformInterface].
 *
 * API verified against the libbox 1.14.4 bindings shipped in
 * sing-box-for-android 1.14.4 (dexdump of the official SFA APK).
 */
class SingBoxBackend(
    private val handler: CommandServerHandler,
    private val platformInterface: PlatformInterface,
) : TunnelBackend {

    private var commandServer: CommandServer? = null

    override fun start(configJson: String) {
        val server = CommandServer(handler, platformInterface)
        server.start()
        server.startOrReloadService(configJson, OverrideOptions())
        commandServer = server
    }

    override fun stop() {
        val server = commandServer ?: return
        commandServer = null
        runCatching { server.closeService() }
        server.close()
    }

    /** Re-applies a (possibly new) configuration without restarting. */
    fun reload(configJson: String) {
        commandServer?.startOrReloadService(configJson, OverrideOptions())
    }

    fun needWIFIState(): Boolean = commandServer?.needWIFIState() ?: false
}

/**
 * Phase 2 stub — never instantiated in v0.1.
 *
 * A real external-process backend launches a native helper (for example
 * `udp-zivpn` for ZIVPN or `dnstt-client` for DNSTT) as a child process,
 * waits until its local SOCKS5 endpoint answers, and only then lets the
 * engine start the sing-box backend. Its `stop()` kills the child
 * process. See `docs/PHASE2.md` for the rules (clean-room only, never
 * run a Hamo binary, never execute a downloaded install script).
 */
class ExternalProcessBackend(private val profile: Profile) : TunnelBackend {
    override fun start(configJson: String): Nothing {
        throw UnsupportedOperationException(
            "External-process backends (${profile.type}) are planned for " +
                "Phase 2 — see docs/PHASE2.md",
        )
    }

    override fun stop() {
        // Nothing was started in v0.1.
    }
}
