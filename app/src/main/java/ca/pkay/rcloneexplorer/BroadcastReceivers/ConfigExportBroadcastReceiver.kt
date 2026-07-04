package ca.pkay.rcloneexplorer.BroadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ca.pkay.rcloneexplorer.util.ConfigExportBroadcastContract
import ca.pkay.rcloneexplorer.workmanager.ConfigExportBroadcastWorker

class ConfigExportBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfigExportBroadcastContract.ACTION) {
            return
        }

        ConfigExportBroadcastWorker.enqueue(
            context = context.applicationContext,
            exportPath = intent.getStringExtra(ConfigExportBroadcastContract.EXTRA_EXPORT_PATH),
            exportFilename = intent.getStringExtra(ConfigExportBroadcastContract.EXTRA_EXPORT_FILENAME),
        )
    }
}
