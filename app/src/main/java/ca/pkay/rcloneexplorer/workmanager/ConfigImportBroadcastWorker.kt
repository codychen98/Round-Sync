package ca.pkay.rcloneexplorer.workmanager

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.notifications.ConfigBroadcastNotifications
import ca.pkay.rcloneexplorer.util.ConfigBackupPathValidator
import ca.pkay.rcloneexplorer.util.ConfigImportBroadcastContract
import ca.pkay.rcloneexplorer.util.ConfigImporter
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ConfigImportBroadcastWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo(applicationContext))

        if (!PermissionManager(applicationContext).grantedStorage()) {
            FLog.e(TAG, "Config import broadcast rejected: storage permission not granted")
            ConfigBroadcastNotifications.showImportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_import_broadcast_failure_storage),
            )
            return@withContext Result.failure()
        }

        val importPath = inputData.getString(ConfigImportBroadcastContract.EXTRA_IMPORT_PATH)
        val importFilename = inputData.getString(ConfigImportBroadcastContract.EXTRA_IMPORT_FILENAME)

        val sourceResult = ConfigBackupPathValidator.resolveImportSource(
            importPath = importPath,
            importFilename = importFilename,
            appPackageName = applicationContext.packageName,
        )
        val sourceFile = sourceResult.getOrElse { error ->
            FLog.e(TAG, "Config import broadcast rejected: %s", error, error.message)
            ConfigBroadcastNotifications.showImportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_import_broadcast_failure_source),
            )
            return@withContext Result.failure()
        }

        when (ConfigImporter.importFromFile(applicationContext, sourceFile).status) {
            ConfigImporter.Status.SUCCESS -> {
                val rclone = Rclone(applicationContext)
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
                ConfigBroadcastNotifications.showImportSuccess(applicationContext, rclone.isConfigEncrypted)
                Result.success()
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
                    applicationContext,
                    applicationContext.getString(R.string.config_import_broadcast_failure_invalid_conf),
                )
                Result.failure()
            }
            ConfigImporter.Status.FAILURE_ZIP_MISSING_CONF -> {
                FLog.e(
                    TAG,
                    "Config import broadcast failed: backup at %s does not contain rclone.conf",
                    sourceFile.absolutePath,
                )
                ConfigBroadcastNotifications.showImportFailure(
                    applicationContext,
                    applicationContext.getString(R.string.config_import_broadcast_failure_missing_conf),
                )
                Result.failure()
            }
            ConfigImporter.Status.FAILURE_UNSPECIFIED -> {
                FLog.e(
                    TAG,
                    "Config import broadcast failed for %s",
                    sourceFile.absolutePath,
                )
                ConfigBroadcastNotifications.showImportFailure(
                    applicationContext,
                    applicationContext.getString(R.string.config_import_broadcast_failure_generic),
                )
                Result.failure()
            }
        }
    }

    private fun createForegroundInfo(context: Context): ForegroundInfo {
        NotificationUtils.createNotificationChannel(
            context,
            ConfigBroadcastNotifications.CHANNEL_ID,
            context.getString(R.string.config_broadcast_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            context.getString(R.string.config_broadcast_notification_channel_description),
        )
        val notification = NotificationCompat.Builder(context, ConfigBroadcastNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_import)
            .setContentTitle(context.getString(R.string.config_import_broadcast_progress_title))
            .setContentText(context.getString(R.string.config_import_broadcast_progress_message))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "ConfigImportBroadcast"
        private const val FOREGROUND_NOTIFICATION_ID = 9106

        @JvmStatic
        fun enqueue(context: Context, importPath: String?, importFilename: String?) {
            val input = Data.Builder()
                .putString(ConfigImportBroadcastContract.EXTRA_IMPORT_PATH, importPath)
                .putString(ConfigImportBroadcastContract.EXTRA_IMPORT_FILENAME, importFilename)
                .build()
            val request = OneTimeWorkRequestBuilder<ConfigImportBroadcastWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
