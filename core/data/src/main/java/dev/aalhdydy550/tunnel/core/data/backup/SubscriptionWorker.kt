package dev.aalhdydy550.tunnel.core.data.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aalhdydy550.tunnel.core.data.net.defaultOkHttpClient
import dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository
import dev.aalhdydy550.tunnel.core.database.TunnelDatabaseHolder
import java.util.concurrent.TimeUnit

/**
 * Daily subscription refresh.
 *
 * Fetches every saved subscription URL and replaces the profiles that
 * came from it — the same replace-only-its-own-profiles rule as a
 * manual refresh (see
 * [SubscriptionRepository][dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository]).
 * A failed fetch keeps the old profiles and records the error on the
 * subscription; a failing URL is never retried in a tight loop —
 * [doWork] returns `Result.retry()` only on a database-level failure
 * (fetch errors are caught per subscription inside
 * [SubscriptionRepository][dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository]).
 *
 * The worker uses the shared database instance ([TunnelDatabaseHolder])
 * and the shared HTTP client ([defaultOkHttpClient]) — the same ones
 * the app uses — so it never builds a second Room instance on the
 * same file, and never closes it.
 */
class SubscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val database = TunnelDatabaseHolder.get(applicationContext)
            val repository = SubscriptionRepository(
                database.subscriptionDao(),
                database.profileDao(),
                defaultOkHttpClient(),
            )
            repository.refreshAll()
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() },
        )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "tunnel-subscription-refresh"

        /** Enqueues the daily refresh (kept across app updates). */
        fun schedule(context: Context) {
            val request =
                PeriodicWorkRequestBuilder<SubscriptionWorker>(24, TimeUnit.HOURS)
                    .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
