package dev.aalhdydy550.tunnel.feature.connection.update

import dev.aalhdydy550.tunnel.core.data.update.InstalledVersion
import dev.aalhdydy550.tunnel.core.data.update.InstalledVersionProvider
import dev.aalhdydy550.tunnel.core.data.update.ReleaseAsset
import dev.aalhdydy550.tunnel.core.data.update.ReleaseInfo
import dev.aalhdydy550.tunnel.core.data.update.UpdateCheckError
import dev.aalhdydy550.tunnel.core.data.update.UpdateChecker
import dev.aalhdydy550.tunnel.core.data.update.UpdateDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * Unit tests for [UpdateCheckViewModel] with hand-written fakes —
 * no Android stubs anywhere, so the whole state machine runs on
 * the JVM. Main is an unconfined test dispatcher, so ViewModel
 * launches run eagerly and assertions read the StateFlow directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UpdateCheckViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    private class FakeUpdateChecker(var result: Result<ReleaseInfo>) : UpdateChecker {
        override suspend fun checkLatest(apiUrl: String): Result<ReleaseInfo> = result
    }

    private sealed interface DownloadBehaviour {
        data class Success(val file: File) : DownloadBehaviour
        data class Failure(val error: Throwable) : DownloadBehaviour
        data object Hang : DownloadBehaviour
    }

    private class FakeUpdateDownloader : UpdateDownloader {
        var behaviour: DownloadBehaviour = DownloadBehaviour.Success(File("update.apk"))
        val progressSeen = mutableListOf<Pair<Long, Long?>>()

        override suspend fun download(
            url: String,
            onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
        ): Result<File> = when (val b = behaviour) {
            is DownloadBehaviour.Success -> {
                onProgress(50_000, 100_000)
                progressSeen += 50_000L to 100_000L
                onProgress(100_000, 100_000)
                progressSeen += 100_000L to 100_000L
                Result.success(b.file)
            }
            is DownloadBehaviour.Failure -> Result.failure(b.error)
            DownloadBehaviour.Hang -> {
                delay(60_000)
                Result.success(File("update.apk"))
            }
        }
    }

    private class FakeInstalledVersionProvider(val version: InstalledVersion) :
        InstalledVersionProvider {
        override fun installed(): InstalledVersion = version
    }

    private fun release(tag: String, code: Long? = null): ReleaseInfo {
        val assetName = if (code != null) {
            "ayman-tunnel-plus-$tag-$code.apk"
        } else {
            "ayman-tunnel-plus-$tag.apk"
        }
        return ReleaseInfo(
            tag,
            tag,
            "2026-11-24T10:00:00Z",
            listOf(ReleaseAsset(assetName, 1024, "http://example.invalid/a.apk")),
        )
    }

    private fun viewModel(
        installed: InstalledVersion = InstalledVersion("0.1.0", 1, null),
        checkerResult: Result<ReleaseInfo> = Result.success(release("v0.1.0")),
        downloader: FakeUpdateDownloader = FakeUpdateDownloader(),
    ): UpdateCheckViewModel = UpdateCheckViewModel(
        checker = FakeUpdateChecker(checkerResult),
        downloader = downloader,
        versionProvider = FakeInstalledVersionProvider(installed),
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Idle with the installed version`() = runTest(testDispatcher) {
        val vm = viewModel()
        val state = vm.uiState.value
        assertTrue(state is UpdateCheckUiState.Idle)
        assertEquals("0.1.0", state.installed.versionName)
        assertEquals(1L, state.installed.versionCode)
    }

    @Test
    fun `F-Droid install starts StoreManaged and the check is a no-op`() =
        runTest(testDispatcher) {
            val vm = viewModel(
                installed = InstalledVersion("0.1.0", 1, "org.fdroid.fdroid"),
            )
            assertTrue(vm.uiState.value is UpdateCheckUiState.StoreManaged)
            vm.checkForUpdates()
            advanceUntilIdle()
            assertTrue(vm.uiState.value is UpdateCheckUiState.StoreManaged)
        }

    @Test
    fun `check finds a newer release`() = runTest(testDispatcher) {
        val vm = viewModel(checkerResult = Result.success(release("v0.2.0")))
        vm.checkForUpdates()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is UpdateCheckUiState.UpdateAvailable)
        assertEquals("v0.2.0", (state as UpdateCheckUiState.UpdateAvailable).release.tag)
    }

    @Test
    fun `check reports up to date on the same tag`() = runTest(testDispatcher) {
        val vm = viewModel(checkerResult = Result.success(release("v0.1.0")))
        vm.checkForUpdates()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is UpdateCheckUiState.UpToDate)
        assertEquals("v0.1.0", (state as UpdateCheckUiState.UpToDate).releaseTag)
    }

    @Test
    fun `check maps 404 to a no-release message`() = runTest(testDispatcher) {
        val vm = viewModel(
            checkerResult = Result.failure(UpdateCheckError.HttpError(404)),
        )
        vm.checkForUpdates()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is UpdateCheckUiState.CheckFailed)
        assertTrue((state as UpdateCheckUiState.CheckFailed).errorMessage.contains("لا يوجد إصدار"))
    }

    @Test
    fun `check maps a network failure to a connectivity message`() = runTest(testDispatcher) {
        val vm = viewModel(
            checkerResult = Result.failure(UpdateCheckError.Network(IOException("down"))),
        )
        vm.checkForUpdates()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is UpdateCheckUiState.CheckFailed)
        assertTrue((state as UpdateCheckUiState.CheckFailed).errorMessage.contains("اتصال"))
    }

    @Test
    fun `download reports progress then lands on Downloaded`() = runTest(testDispatcher) {
        val downloader = FakeUpdateDownloader()
        val vm = viewModel(
            checkerResult = Result.success(release("v0.2.0")),
            downloader = downloader,
        )
        val seen = mutableListOf<UpdateCheckUiState>()
        backgroundScope.launch { vm.uiState.collect { seen.add(it) } }
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        val state = vm.uiState.value
        assertTrue(state is UpdateCheckUiState.Downloaded)
        assertEquals(
            File("update.apk"),
            (state as UpdateCheckUiState.Downloaded).apkFile,
        )
        assertEquals(
            listOf(50_000L to 100_000L, 100_000L to 100_000L),
            downloader.progressSeen,
        )
        assertTrue(seen.any { it is UpdateCheckUiState.Downloading })
    }

    @Test
    fun `download failure can be retried`() = runTest(testDispatcher) {
        val downloader = FakeUpdateDownloader().apply {
            behaviour = DownloadBehaviour.Failure(UpdateCheckError.HttpError(500))
        }
        val vm = viewModel(
            checkerResult = Result.success(release("v0.2.0")),
            downloader = downloader,
        )
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.DownloadFailed)
        downloader.behaviour = DownloadBehaviour.Success(File("update.apk"))
        vm.downloadUpdate()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.Downloaded)
    }

    @Test
    fun `cancelling a download returns to UpdateAvailable`() = runTest(testDispatcher) {
        val downloader = FakeUpdateDownloader().apply {
            behaviour = DownloadBehaviour.Hang
        }
        val vm = viewModel(
            checkerResult = Result.success(release("v0.2.0")),
            downloader = downloader,
        )
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.Downloading)
        vm.cancelDownload()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.UpdateAvailable)
    }

    @Test
    fun `download from Idle is a no-op`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.downloadUpdate()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.Idle)
    }

    @Test
    fun `resetToIdle returns from DownloadFailed`() = runTest(testDispatcher) {
        val downloader = FakeUpdateDownloader().apply {
            behaviour = DownloadBehaviour.Failure(UpdateCheckError.HttpError(500))
        }
        val vm = viewModel(
            checkerResult = Result.success(release("v0.2.0")),
            downloader = downloader,
        )
        vm.checkForUpdates()
        advanceUntilIdle()
        vm.downloadUpdate()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.DownloadFailed)
        vm.resetToIdle()
        assertTrue(vm.uiState.value is UpdateCheckUiState.Idle)
    }
}
