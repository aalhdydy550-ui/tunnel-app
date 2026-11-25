package dev.aalhdydy550.tunnel.feature.connection.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.aalhdydy550.tunnel.core.data.update.InstalledVersion
import dev.aalhdydy550.tunnel.core.data.update.InstallSource
import dev.aalhdydy550.tunnel.core.data.update.UpdateCheckError
import dev.aalhdydy550.tunnel.core.data.update.UpdateChecker
import dev.aalhdydy550.tunnel.core.data.update.UpdateDecision
import dev.aalhdydy550.tunnel.core.data.update.UpdateDownloader
import dev.aalhdydy550.tunnel.core.data.update.InstalledVersionProvider
import dev.aalhdydy550.tunnel.core.data.update.decideUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manual update check (no polling, no periodic work, no service).
 *
 * The user taps "check": one GitHub API call, then — only if a newer
 * release exists — one APK download the user starts and can cancel.
 * The installed version is read once at startup; a build installed
 * from F-Droid or Play stays [UpdateCheckUiState.StoreManaged] and
 * the check button does nothing there, because those builds are
 * updated by their store.
 *
 * Installing is deliberately NOT here: the screen owns the install
 * intent ([UpdateInstaller]) with the activity context, so this
 * ViewModel stays unit-testable on the JVM with no Android stubs.
 */
@HiltViewModel
class UpdateCheckViewModel @Inject constructor(
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val versionProvider: InstalledVersionProvider,
) : ViewModel() {

    private val _uiState = MutableStateFlow(initialState())
    val uiState: StateFlow<UpdateCheckUiState> = _uiState.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * The installed build, read once at startup. A store-installed
     * build (F-Droid / Play) starts StoreManaged; a sideloaded build
     * starts Idle. A PackageManager failure degrades to "0.0.0" so
     * the screen still opens instead of crashing the ViewModel.
     */
    private fun initialState(): UpdateCheckUiState {
        val installed = runCatching { versionProvider.installed() }
            .getOrElse { InstalledVersion("0.0.0", 0, null) }
        return if (installed.installSource == InstallSource.SIDELOADED) {
            UpdateCheckUiState.Idle(installed)
        } else {
            UpdateCheckUiState.StoreManaged(installed)
        }
    }

    /** One manual check against GitHub Releases. No-op while checking or downloading. */
    fun checkForUpdates() {
        val current = _uiState.value
        if (current is UpdateCheckUiState.StoreManaged ||
            current is UpdateCheckUiState.Checking ||
            current is UpdateCheckUiState.Downloading
        ) {
            return
        }
        val installed = current.installed
        viewModelScope.launch {
            _uiState.value = UpdateCheckUiState.Checking(installed)
            val result = checker.checkLatest()
            result.fold(
                onSuccess = { release ->
                    _uiState.value = when (val decision = decideUpdate(installed, release)) {
                        is UpdateDecision.UpdateAvailable ->
                            UpdateCheckUiState.UpdateAvailable(installed, release)

                        UpdateDecision.UpToDate ->
                            UpdateCheckUiState.UpToDate(installed, release.tag)

                        UpdateDecision.NoApkAsset ->
                            UpdateCheckUiState.CheckFailed(
                                installed,
                                "الإصدار المنشور بلا ملف APK — حدّث يدويًا من GitHub",
                            )

                        UpdateDecision.CannotDetermine ->
                            UpdateCheckUiState.CheckFailed(
                                installed,
                                "تعذّرت مقارنة الإصدار — حدّث يدويًا من GitHub",
                            )
                    }
                },
                onFailure = { error ->
                    _uiState.value = UpdateCheckUiState.CheckFailed(installed, mapError(error))
                },
            )
        }
    }

    /** Starts (or retries) the APK download for the offered release. */
    fun downloadUpdate() {
        val (installed, release) = when (val state = _uiState.value) {
            is UpdateCheckUiState.UpdateAvailable -> state.installed to state.release
            is UpdateCheckUiState.DownloadFailed -> state.installed to state.release
            else -> return
        }
        val apk = release.apkAsset ?: return
        downloadJob = viewModelScope.launch {
            _uiState.value = UpdateCheckUiState.Downloading(installed, release, 0, null)
            try {
                val result = downloader.download(apk.downloadUrl) { downloaded, total ->
                    val current = _uiState.value
                    if (current is UpdateCheckUiState.Downloading) {
                        _uiState.value = current.copy(
                            downloadedBytes = downloaded,
                            totalBytes = total,
                        )
                    }
                }
                result.fold(
                    onSuccess = { file ->
                        _uiState.value = UpdateCheckUiState.Downloaded(installed, release, file)
                    },
                    onFailure = { error ->
                        _uiState.value = UpdateCheckUiState.DownloadFailed(
                            installed,
                            release,
                            mapError(error),
                        )
                    },
                )
            } catch (e: CancellationException) {
                // User cancelled: back to "update available", no error.
                _uiState.value = UpdateCheckUiState.UpdateAvailable(installed, release)
                throw e
            }
        }
    }

    /** Cancels an in-flight download; the partial file is deleted by the downloader. */
    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Back to the idle state (keeps the installed version; store builds stay managed). */
    fun resetToIdle() {
        val current = _uiState.value
        if (current is UpdateCheckUiState.StoreManaged) return
        _uiState.value = UpdateCheckUiState.Idle(current.installed)
    }

    internal fun mapError(error: Throwable): String = when (error) {
        is UpdateCheckError.HttpError -> when (error.code) {
            403, 429 -> "تجاوز حد طلبات GitHub المجانية (60 طلبًا في الساعة) — حاول بعد ساعة"
            404 -> "لا يوجد إصدار منشور بعد على GitHub"
            else -> "تعذّر الفحص (HTTP ${error.code}) — تحقق من الاتصال وحاول مجددًا"
        }
        is UpdateCheckError.Network -> "لا يوجد اتصال بالإنترنت — تحقق من الشبكة وحاول مجددًا"
        is UpdateCheckError.BadResponse -> "رد غير متوقع من GitHub — حاول مجددًا"
        else -> "تعذّر الفحص: ${error.message ?: "خطأ غير معروف"}"
    }
}
