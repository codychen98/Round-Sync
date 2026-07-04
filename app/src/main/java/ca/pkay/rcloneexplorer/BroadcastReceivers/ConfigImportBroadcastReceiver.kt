package ca.pkay.rcloneexplorer.BroadcastReceivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.ConfigBroadcastNotifications
import ca.pkay.rcloneexplorer.util.ConfigBackupPathValidator
import ca.pkay.rcloneexplorer.util.ConfigImportBroadcastContract
import ca.pkay.rcloneexplorer.util.ConfigImporter
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.PermissionManager

class ConfigImportBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ConfigImportBroadcastContract.ACTION) {
            return
        }

        val appContext = context.applicationContext
        val pendingResult = goAsync()

        Thread {
            try {
                handleImport(appContext, intent)
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    private fun handleImport(context: Context, intent: Intent) {
        if (!PermissionManager(context).grantedStorage()) {
            FLog.e(TAG, "Config import broadcast rejected: storage permission not granted")
            ConfigBroadcastNotifications.showImportFailure(
                context,
                context.getString(R.string.config_import_broadcast_failure_storage),
            )
            return
        }

        val sourceResult = ConfigBackupPathValidator.resolveImportSource(
            importPath = intent.getStringExtra(ConfigImportBroadcastContract.EXTRA_IMPORT_PATH),
            importFilename = intent.getStringExtra(ConfigImportBroadcastContract.EXTRA_IMPORT_FILENAME),
            appPackageName = context.packageName,
        )
        val sourceFile = sourceResult.getOrElse { error ->
            FLog.e(TAG, "Config import broadcast rejected: %s", error, error.message)
            ConfigBroadcastNotifications.showImportFailure(
                context,
                context.getString(R.string.config_import_broadcast_failure_source),
            )
            return
        }

        when (ConfigImporter.importFromFile(context, sourceFile).status) {
            ConfigImporter.Status.SUCCESS -> {
                val rclone = Rclone(context)
                if (rclone.isConfigEncrypted) {
                    FLog.i(
                        TAG,
                        "Config imported via broadcast from %s; open the app to enter the config password",
                        sourceFile.absolutePath,
                    )
                } else {
                    FLog.i(
                        TAG,
                        "Config imported via broadcast from %s",
                        sourceFile.absolutePath,
                    )
                }
                ConfigBroadcastNotifications.showImportSuccess(context, rclone.isConfigEncrypted)
            }
            ConfigImporter.Status.FAILURE_RCLONE_CONF_NOT_VALID,
            ConfigImporter.Status.FAILURE_ZIP_INVALID_CONF,
            -> {
                FLog.e(
                    TAG,
                    "Config import broadcast failed: backup at %s is not a valid rclone.conf",
                    sourceFile.absolutePath,
                )
                ConfigBroadcastNotifications.showImportFailure(
                    context,
                    context.getString(R.string.config_import_broadcast_failure_invalid_conf),
                )
            }
            ConfigImporter.Status.FAILURE_ZIP_MISSING_CONF -> {
                FLog.e(
                    TAG,
                    "Config import broadcast failed: backup at %s does not contain rclone.conf",
                    sourceFile.absolutePath,
                )
                ConfigBroadcastNotifications.showImportFailure(
                    context,
                    context.getString(R.string.config_import_broadcast_failure_missing_conf),
                )
            }
            ConfigImporter.Status.FAILURE_UNSPECIFIED -> {
                FLog.e(
                    TAG,
                    "Config import broadcast failed for %s",
                    sourceFile.absolutePath,
                )
                ConfigBroadcastNotifications.showImportFailure(
                    context,
                    context.getString(R.string.config_import_broadcast_failure_generic),
                )
            }
        }
    }

    companion object {
        private const val TAG = "ConfigImportBroadcast"
    }
}
