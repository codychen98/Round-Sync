package ca.pkay.rcloneexplorer

import android.app.Application
import ca.pkay.rcloneexplorer.util.SyncLog

class RoundSyncApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        SyncLog.startSession(this)
    }
}
