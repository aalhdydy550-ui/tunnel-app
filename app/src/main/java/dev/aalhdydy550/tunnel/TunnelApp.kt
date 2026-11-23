package dev.aalhdydy550.tunnel

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.aalhdydy550.tunnel.core.data.backup.BackupWorker
import dev.aalhdydy550.tunnel.core.data.backup.SubscriptionWorker

/** Application entry point (Hilt + the daily profile backup + subscription refresh). */
@HiltAndroidApp
class TunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BackupWorker.schedule(this)
        SubscriptionWorker.schedule(this)
    }
}
