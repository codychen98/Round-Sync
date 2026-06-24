package ca.pkay.rcloneexplorer.workmanager

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import ca.pkay.rcloneexplorer.util.LastFolderSnapshotStore
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchExecutor
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * After the explorer closes, prefetches HTTP thumbnails for the last-opened folder (non-recursive
 * listing), gated by the same rules as the file explorer adapter.
 *
 * Uses [CoroutineWorker] + [setForeground] with `dataSync` so thumbnail prep can continue after the
 * app is swiped away (feature.md Q16 / plan E.3), alongside [ThumbnailServerService] progress.
 */
class LastFolderThumbnailPrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val snapshot = LastFolderSnapshotStore.read(prefs) ?: return@withContext Result.success()
        if (!ThumbnailPrefetchTargets.readShowThumbnails(prefs, app)) {
            return@withContext Result.success()
        }
        val wifiOnly = prefs.getBoolean(app.getString(R.string.pref_key_wifi_only_transfers), false)
        if (wifiOnly && WifiConnectivitiyUtil.dataConnection(app) == WifiConnectivitiyUtil.Connection.METERED) {
            return@withContext Result.success()
        }
        val rclone = Rclone(app)
        val remote = rclone.getRemoteItemFromName(snapshot.remoteName) ?: return@withContext Result.success()
        if (remote.isRemoteType(RemoteItem.SAFW)) {
            return@withContext Result.success()
        }
        val startAtRoot = ThumbnailPrefetchTargets.readStartAtRoot(prefs, app)
        val listing = rclone.getDirectoryContent(remote, snapshot.directoryPath, startAtRoot)
            ?: return@withContext Result.success()
        val sizeLimit = ThumbnailPrefetchTargets.readThumbnailSizeLimitBytes(prefs, app)
        val targets = ThumbnailPrefetchTargets.filterForHttpThumbnailPrefetch(
            listing,
            remote,
            prefs,
            sizeLimit,
            showThumbnails = true,
        )
        if (targets.isEmpty()) {
            return@withContext Result.success()
        }

        SyncLog.info(
            app,
            "MediaPrepDbg",
            "event=prefetchSetForeground path=${snapshot.directoryPath} targets=${targets.size}",
        )
        setForeground(createPrefetchForegroundInfo(app, snapshot.directoryPath))

        ThumbnailPrefetchExecutor.prefetchFolder(
            app = app,
            remote = remote,
            directoryPath = snapshot.directoryPath,
            targets = targets,
            isStopped = { isStopped },
            onProgress = { progress ->
                SyncLog.info(
                    app,
                    "MediaPrepDbg",
                    "event=prefetchUpdateProgress phase=item loaded=${progress.loaded} " +
                        "total=${progress.total} path=${progress.directoryPath}",
                )
            },
        )
        Result.success()
    }

    private fun createPrefetchForegroundInfo(context: Context, folderDisplayPath: String): ForegroundInfo {
        NotificationUtils.createNotificationChannel(
            context,
            ThumbnailServerService.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.background_media_prep_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            context.getString(R.string.thumbnail_server_notification_channel_description),
        )
        val folderLine = context.getString(R.string.notification_media_prep_folder_line, folderDisplayPath)
        val notification = NotificationCompat.Builder(context, ThumbnailServerService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle(context.getString(R.string.background_media_prep_notification_title))
            .setContentText(folderLine)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                WM_FOREGROUND_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(WM_FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "LastFolderThumbPrefetch"
        const val UNIQUE_WORK_NAME = "last_folder_thumbnail_prefetch_v1"
        private const val WM_FOREGROUND_NOTIFICATION_ID = 183

        @JvmStatic
        fun enqueue(context: Context) {
            val app = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<LastFolderThumbnailPrefetchWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            SyncLog.info(app, TAG, "Enqueued last-folder thumbnail prefetch work")
        }
    }
}
