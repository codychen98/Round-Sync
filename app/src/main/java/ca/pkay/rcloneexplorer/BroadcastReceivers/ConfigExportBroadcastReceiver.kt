package ca.pkay.rcloneexplorer.BroadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.ConfigExportBroadcastContract
import ca.pkay.rcloneexplorer.util.ConfigBackupPathValidator
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.PermissionManager
import java.io.IOException

class ConfigExportBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfigExportBroadcastContract.ACTION) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        Thread {
            try {
                handleExport(appContext, intent)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun handleExport(context: Context, intent: Intent) {
        if (!PermissionManager(context).grantedStorage()) {
            FLog.e(TAG, "Config export broadcast rejected: storage permission not granted")
            return
        }

        val destinationResult = ConfigBackupPathValidator.resolveExportDestination(
            exportPath = intent.getStringExtra(ConfigExportBroadcastContract.EXTRA_EXPORT_PATH),
            exportFilename = intent.getStringExtra(ConfigExportBroadcastContract.EXTRA_EXPORT_FILENAME),
            appPackageName = context.packageName,
        )
        val destination = destinationResult.getOrElse { error ->
            FLog.e(TAG, "Config export broadcast rejected: %s", error, error.message)
            return
        }

        val rclone = Rclone(context)
        if (!rclone.isConfigFileCreated) {
            FLog.e(TAG, "Config export broadcast rejected: no config found")
            return
        }

        val parent = destination.outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            FLog.e(TAG, "Config export broadcast failed: could not create %s", parent.absolutePath)
            return
        }

        try {
            rclone.exportConfigFile(destination.outputFile)
            FLog.i(
                TAG,
                "Config exported via broadcast to %s",
                destination.outputFile.absolutePath,
            )
        } catch (e: IOException) {
            FLog.e(
                TAG,
                "Config export broadcast failed for %s",
                e,
                destination.outputFile.absolutePath,
            )
        }
    }

    companion object {
        private const val TAG = "ConfigExportBroadcast"
    }
}
