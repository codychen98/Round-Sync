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
import ca.pkay.rcloneexplorer.util.CacheArchiveExporter
import ca.pkay.rcloneexplorer.util.ConfigBackupPathValidator
import ca.pkay.rcloneexplorer.util.ConfigExportBroadcastContract
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.PermissionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class ConfigExportBroadcastWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(createForegroundInfo(applicationContext))

        if (!PermissionManager(applicationContext).grantedStorage()) {
            FLog.e(TAG, "Config export broadcast rejected: storage permission not granted")
            ConfigBroadcastNotifications.showExportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_export_broadcast_failure_storage),
            )
            return@withContext Result.failure()
        }

        val exportPath = inputData.getString(ConfigExportBroadcastContract.EXTRA_EXPORT_PATH)
        val exportFilename = inputData.getString(ConfigExportBroadcastContract.EXTRA_EXPORT_FILENAME)

        val destinationResult = ConfigBackupPathValidator.resolveExportDestination(
            exportPath = exportPath,
            exportFilename = exportFilename,
            appPackageName = applicationContext.packageName,
        )
        val destination = destinationResult.getOrElse { error ->
            FLog.e(TAG, "Config export broadcast rejected: %s", error, error.message)
            ConfigBroadcastNotifications.showExportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_export_broadcast_failure_destination),
            )
            return@withContext Result.failure()
        }

        val rclone = Rclone(applicationContext)
        if (!rclone.isConfigFileCreated) {
            FLog.e(TAG, "Config export broadcast rejected: no config found")
            ConfigBroadcastNotifications.showExportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_export_broadcast_failure_no_config),
            )
            return@withContext Result.failure()
        }

        val parent = destination.outputFile.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            FLog.e(TAG, "Config export broadcast failed: could not create %s", parent.absolutePath)
            ConfigBroadcastNotifications.showExportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_export_broadcast_failure_destination),
            )
            return@withContext Result.failure()
        }

        try {
            rclone.exportConfigFile(destination.outputFile)
            val includingCache = CacheArchiveExporter.hasCacheEntriesToExport(applicationContext)
            FLog.i(
                TAG,
                "Config exported via broadcast to %s",
                destination.outputFile.absolutePath,
            )
            ConfigBroadcastNotifications.showExportSuccess(applicationContext, includingCache)
            Result.success()
        } catch (e: IOException) {
            FLog.e(
                TAG,
                "Config export broadcast failed for %s",
                e,
                destination.outputFile.absolutePath,
            )
            ConfigBroadcastNotifications.showExportFailure(
                applicationContext,
                applicationContext.getString(R.string.config_export_broadcast_failure_generic),
            )
            Result.failure()
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
            .setSmallIcon(R.drawable.ic_export)
            .setContentTitle(context.getString(R.string.config_export_broadcast_progress_title))
            .setContentText(context.getString(R.string.config_export_broadcast_progress_message))
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
        private const val TAG = "ConfigExportBroadcast"
        private const val FOREGROUND_NOTIFICATION_ID = 9105

        @JvmStatic
        fun enqueue(context: Context, exportPath: String?, exportFilename: String?) {
            val input = Data.Builder()
                .putString(ConfigExportBroadcastContract.EXTRA_EXPORT_PATH, exportPath)
                .putString(ConfigExportBroadcastContract.EXTRA_EXPORT_FILENAME, exportFilename)
                .build()
            val request = OneTimeWorkRequestBuilder<ConfigExportBroadcastWorker>()
                .setInputData(input)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueue(request)
        }
    }
}
