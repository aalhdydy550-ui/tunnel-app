package dev.aalhdydy550.tunnel.core.data.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Downloads a release APK straight to the app's cache (no
 * DownloadManager, no notification, no service: the download lives
 * only while the update screen is open, and the user starts and
 * cancels it by hand).
 *
 * The [onProgress] callback runs on the IO thread — it must be
 * thread-safe (updating a StateFlow is fine). On failure or
 * cancellation the partial file is deleted, so a half APK is never
 * offered for install. Cancellation aborts the HTTP call at once
 * (the call is cancelled with the coroutine), not only between
 * buffer reads.
 */
interface UpdateDownloader {
    suspend fun download(
        url: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<File>
}

class OkHttpUpdateDownloader(
    private val client: OkHttpClient,
    /** The app's cache dir; the APK goes to `<cacheDir>/updates/update.apk`. */
    private val cacheDir: File,
) : UpdateDownloader {

    override suspend fun download(
        url: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        val destDir = File(cacheDir, "updates")
        val dest = File(destDir, "update.apk")
        val tmp = File(destDir, "update.apk.tmp")
        try {
            destDir.mkdirs()
            // A previous run killed mid-download (process death) may
            // have left a stale temp file; never resume or install it.
            tmp.delete()
            val call = client.newCall(Request.Builder().url(url).build())
            // Abort the HTTP call the moment the coroutine is cancelled,
            // even while waiting for the first byte (a read timeout
            // alone would leave the user staring at a stuck bar for
            // 30s). NOTE: the original code used
            // Job.invokeOnCancellation, which was removed in
            // kotlinx-coroutines 1.9 (project uses 1.11.0), and the
            // invokeOnCompletion(onCancelling = true) overload is
            // internal API — so the blocking execute() is replaced
            // with enqueue() under suspendCancellableCoroutine, whose
            // public invokeOnCancellation hook cancels the call.
            // (Coordinator fix, 2026-11-25 — the sim compile caught
            // the removed API; behaviour unchanged.)
            executeCancellable(call).use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure<File>(
                        UpdateCheckError.HttpError(response.code),
                    )
                }
                val body = response.body
                    ?: return@withContext Result.failure<File>(
                        UpdateCheckError.BadResponse("Empty response body"),
                    )
                val total = body.contentLength().takeIf { it >= 0 }
                var downloaded = 0L
                body.byteStream().use { input ->
                    tmp.outputStream().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read == -1) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (total != null && downloaded != total) {
                    throw UpdateCheckError.BadResponse(
                        "Truncated download: $downloaded of $total bytes",
                    )
                }
            }
            // Publish: rename the finished temp file over any old APK.
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Result.success(dest)
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (e: UpdateCheckError) {
            tmp.delete()
            Result.failure(e)
        } catch (e: IOException) {
            tmp.delete()
            // A cancelled OkHttp call surfaces as IOException
            // ("Canceled"). Re-check cancellation first so a user
            // cancel is a cancel — not a "network error".
            currentCoroutineContext().ensureActive()
            Result.failure(UpdateCheckError.Network(e))
        } catch (e: Exception) {
            tmp.delete()
            Result.failure(e)
        }
    }

    /**
     * Runs [call] with OkHttp's async API so cancellation aborts the
     * socket at once: [suspendCancellableCoroutine] gives a public
     * cancellation hook, which the blocking `execute()` has no
     * equivalent for on kotlinx-coroutines 1.11. On failure while
     * the coroutine is already cancelled the callback returns
     * without resuming — the coroutine then throws
     * CancellationException itself.
     */
    private suspend fun executeCancellable(call: Call): Response =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }

                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isCancelled) return
                    cont.resumeWithException(e)
                }
            })
        }

    private companion object {
        const val DOWNLOAD_BUFFER_BYTES = 8192
    }
}
