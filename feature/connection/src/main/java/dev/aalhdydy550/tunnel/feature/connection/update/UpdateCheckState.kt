package dev.aalhdydy550.tunnel.feature.connection.update

import dev.aalhdydy550.tunnel.core.data.update.InstalledVersion
import dev.aalhdydy550.tunnel.core.data.update.ReleaseInfo
import java.io.File

/**
 * UI states of the manual update check. Every state carries the
 * installed build, so the screen always shows the current version.
 *
 * Flow: Idle → (tap) → Checking → UpToDate | UpdateAvailable |
 * CheckFailed. UpdateAvailable → (tap) → Downloading → Downloaded |
 * DownloadFailed; cancel returns to UpdateAvailable. A build installed
 * from F-Droid or Play never leaves [StoreManaged]: its updates come
 * from the store, not from GitHub.
 */
sealed interface UpdateCheckUiState {

    val installed: InstalledVersion

    /** Nothing checked yet (or the user went back). Tap to check. */
    data class Idle(override val installed: InstalledVersion) : UpdateCheckUiState

    /** The GitHub API call is in flight. */
    data class Checking(override val installed: InstalledVersion) : UpdateCheckUiState

    /** Installed from a store: the in-app GitHub check does not apply. */
    data class StoreManaged(override val installed: InstalledVersion) : UpdateCheckUiState

    /** Installed is the same or newer than the latest published release. */
    data class UpToDate(
        override val installed: InstalledVersion,
        val releaseTag: String,
    ) : UpdateCheckUiState

    /** A newer release exists; its APK is ready to download. */
    data class UpdateAvailable(
        override val installed: InstalledVersion,
        val release: ReleaseInfo,
    ) : UpdateCheckUiState

    /** Download in progress; [totalBytes] is null when the server hides the size. */
    data class Downloading(
        override val installed: InstalledVersion,
        val release: ReleaseInfo,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : UpdateCheckUiState

    /** APK fully downloaded to the cache; ready for the install intent. */
    data class Downloaded(
        override val installed: InstalledVersion,
        val release: ReleaseInfo,
        val apkFile: File,
    ) : UpdateCheckUiState

    /** The check itself failed (no network, GitHub error, bad body). */
    data class CheckFailed(
        override val installed: InstalledVersion,
        val errorMessage: String,
    ) : UpdateCheckUiState

    /** The download failed (or was truncated); retry from here. */
    data class DownloadFailed(
        override val installed: InstalledVersion,
        val release: ReleaseInfo,
        val errorMessage: String,
    ) : UpdateCheckUiState
}
