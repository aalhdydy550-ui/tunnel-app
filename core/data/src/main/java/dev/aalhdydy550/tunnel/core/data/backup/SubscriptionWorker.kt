package dev.aalhdydy550.tunnel.core.data.backup

import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository
import dev.aalhdydy550.tunnel.core.database.TunnelDatabase
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Daily subscription refresh.
 *
 * Fetches every saved subscription URL and replaces the profiles that
 * came from it — the same replace-only-its-own-profiles rule as a
 * manual refresh (see
 * [SubscriptionRepository][dev.aalhdydy550.tunnel.core.data.repository.SubscriptionRepository]).
 * A failed fetch keeps the old profiles and records the error on the
 * subscription; the worker itself always succeeds so WorkManager does
 * not retry a failing URL in a tight loop.
 *
 * The worker builds the database directly (no Hilt worker setup in
 * v0.1) using the same database name and migrations as the app.
 */
class SubscriptionWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val database = Room.databaseBuilder(
                applicationContext,
                TunnelDatabase::class.java,
                "tunnel.db",
            )
                .addMigrations(
                    TunnelDatabase.MIGRATION_1_2,
                    TunnelDatabase.MIGRATION_2_3,
                )
                .fallbackToDestructiveMigration()
                .build()
            try {
                val repository = SubscriptionRepository(
                    database.subscriptionDao(),
                    database.profileDao(),
                    OkHttpClient(),
                )
                repository.refreshAll()
            } finally {
                database.close()
            }
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
