package ca.pkay.rcloneexplorer.Activities

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import ca.pkay.rcloneexplorer.util.ConfigExportBroadcastContract
import ca.pkay.rcloneexplorer.util.ConfigImportBroadcastContract
import ca.pkay.rcloneexplorer.workmanager.ConfigExportBroadcastWorker
import ca.pkay.rcloneexplorer.workmanager.ConfigImportBroadcastWorker

/**
 * Headless entry point for MacroDroid and other automation.
 *
 * Unlike manifest broadcast receivers, an explicit activity start can wake Round Sync
 * after the user force-stops the app.
 */
class ConfigBroadcastActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent.action) {
            ConfigExportBroadcastContract.ACTION -> {
                ConfigExportBroadcastWorker.enqueue(
                    context = applicationContext,
                    exportPath = intent.getStringExtra(ConfigExportBroadcastContract.EXTRA_EXPORT_PATH),
                    exportFilename = intent.getStringExtra(ConfigExportBroadcastContract.EXTRA_EXPORT_FILENAME),
                )
            }
            ConfigImportBroadcastContract.ACTION -> {
                ConfigImportBroadcastWorker.enqueue(
                    context = applicationContext,
                    importPath = intent.getStringExtra(ConfigImportBroadcastContract.EXTRA_IMPORT_PATH),
                    importFilename = intent.getStringExtra(ConfigImportBroadcastContract.EXTRA_IMPORT_FILENAME),
                )
            }
        }

        finish()
    }
}
