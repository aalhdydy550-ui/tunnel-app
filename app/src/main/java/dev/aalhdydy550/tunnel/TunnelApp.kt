package dev.aalhdydy550.tunnel

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.aalhdydy550.tunnel.core.data.backup.BackupWorker

/** Application entry point (Hilt + the daily profile backup). */
@HiltAndroidApp
class TunnelApp : Application() {
    override fun onCreate() {
        super.onCreate()
        BackupWorker.schedule(this)
    }
}
