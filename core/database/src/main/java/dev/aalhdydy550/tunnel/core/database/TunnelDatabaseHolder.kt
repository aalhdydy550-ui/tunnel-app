package dev.aalhdydy550.tunnel.core.database

import android.content.Context
import androidx.room.Room
import kotlin.jvm.Volatile

/**
 * The single shared [TunnelDatabase] instance for the whole app.
 *
 * The app's Hilt graph ([DataModule][dev.aalhdydy550.tunnel.core.data.di.DataModule])
 * and the background workers ([BackupWorker][dev.aalhdydy550.tunnel.core.data.backup.BackupWorker],
 * [SubscriptionWorker][dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker]) must
 * all use this one instance: two Room instances on the same `tunnel.db` file have
 * independent invalidation trackers, so writes made by a worker (e.g. a daily
 * subscription refresh) would not reach the UI's `Flow` until the app re-queried,
 * and concurrent writes from two instances risk conflicts.
 *
 * The instance lives for the lifetime of the process and is never closed by
 * callers — closing it would break the other users of the same instance.
 * A future migration only needs to be added here (plus the app module's
 * [TunnelDatabase] companion), never in three places.
 */
object TunnelDatabaseHolder {

    @Volatile
    private var instance: TunnelDatabase? = null

    fun get(context: Context): TunnelDatabase = instance ?: synchronized(this) {
        instance ?: Room.databaseBuilder(
            context.applicationContext,
            TunnelDatabase::class.java,
            "tunnel.db",
        )
            .addMigrations(
                TunnelDatabase.MIGRATION_1_2,
                TunnelDatabase.MIGRATION_2_3,
            )
            .build()
            .also { instance = it }
    }
}
