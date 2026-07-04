package ca.pkay.rcloneexplorer.BroadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ca.pkay.rcloneexplorer.util.ConfigImportBroadcastContract
import ca.pkay.rcloneexplorer.workmanager.ConfigImportBroadcastWorker

class ConfigImportBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfigImportBroadcastContract.ACTION) {
            return
        }

        ConfigImportBroadcastWorker.enqueue(
            context = context.applicationContext,
            importPath = intent.getStringExtra(ConfigImportBroadcastContract.EXTRA_IMPORT_PATH),
            importFilename = intent.getStringExtra(ConfigImportBroadcastContract.EXTRA_IMPORT_FILENAME),
        )
    }
}
