package dev.aalhdydy550.tunnel

import android.app.Application
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import dev.aalhdydy550.tunnel.core.data.backup.BackupWorker
import dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker
import dev.aalhdydy550.tunnel.core.database.HamoReferenceSeeder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application entry point (Hilt + the daily profile backup + subscription refresh). */
@HiltAndroidApp
class TunnelApp : Application() {

    /**
     * One-shot seeder (runs once per process start, background IO).
     * It is idempotent and failure-proof: a failure is swallowed
     * here (never a startup crash) and retried on the next start —
     * the reference tables are re-checked by the next start's
     * `COUNT(*)` check.
     */
    private val seedScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        BackupWorker.schedule(this)
        SubscriptionWorker.schedule(this)
        val seeds = EntryPointAccessors.fromApplication(
            this,
            TunnelAppSeedEntryPoint::class.java,
        )
        seedScope.launch {
            runCatching { seeds.referenceDataSeeder().ensureSeeded() }
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface TunnelAppSeedEntryPoint {
        fun referenceDataSeeder(): HamoReferenceSeeder
    }
}
