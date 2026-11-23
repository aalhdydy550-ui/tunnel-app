package dev.aalhdydy550.tunnel.core.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aalhdydy550.tunnel.core.data.repository.ProfileRepository
import dev.aalhdydy550.tunnel.core.database.TunnelDatabaseHolder
import kotlinx.coroutines.flow.first
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Daily local backup of the profile list.
 *
 * Writes `tunnel-backup-YYYY-MM-DD.json` (the [exportProfilesToJson]
 * format) into the app-private `filesDir/backups/` directory and keeps
 * the newest 7 files. App-private storage on purpose: unlike the
 * original app's `backups/` folder browsed with a FileDialog over
 * external storage, this never exposes the user's server list to other
 * apps. Sharing a backup with another device is an explicit user
 * action (export from the servers screen via the system share sheet).
 *
 * The worker uses the shared database instance ([TunnelDatabaseHolder])
 * — the same one the app UI observes — so it never builds a second
 * Room instance on the same file, and never closes it.
 */
class BackupWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val database = TunnelDatabaseHolder.get(applicationContext)
            val repository = ProfileRepository(database.profileDao())
            val profiles = repository.profiles.first()
            if (profiles.isEmpty()) return Result.success()
            val dir = File(applicationContext.filesDir, BACKUP_DIR).apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            File(dir, "tunnel-backup-$stamp.json")
                .writeText(exportProfilesToJson(profiles))
            pruneOldBackups(dir)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    private fun pruneOldBackups(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("tunnel-backup-") && it.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            ?: return
        files.drop(MAX_KEPT_BACKUPS).forEach { it.delete() }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "tunnel-daily-backup"
        const val BACKUP_DIR = "backups"
        const val MAX_KEPT_BACKUPS = 7

        /** Enqueues the daily backup (kept across app updates). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<BackupWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
