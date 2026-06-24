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
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import ca.pkay.rcloneexplorer.util.MediaFolderPolicyPrefetchFolders
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchExecutor
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Background prefetch for all thumbnail-enabled media policy folders (non-recursive per folder).
 */
class MediaFolderPolicyThumbnailPrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        if (!ThumbnailPrefetchTargets.readShowThumbnails(prefs, app)) {
            return@withContext Result.success()
        }
        val wifiOnly = prefs.getBoolean(app.getString(R.string.pref_key_wifi_only_transfers), false)
        if (wifiOnly && WifiConnectivitiyUtil.dataConnection(app) == WifiConnectivitiyUtil.Connection.METERED) {
            return@withContext Result.success()
        }

        val rclone = Rclone(app)
        val remotes = rclone.remotes
        val policyFolders = MediaFolderPolicyPrefetchFolders.enumerate(remotes, prefs)
        if (policyFolders.isEmpty()) {
            return@withContext Result.success()
        }

        val startAtRoot = ThumbnailPrefetchTargets.readStartAtRoot(prefs, app)
        val sizeLimit = ThumbnailPrefetchTargets.readThumbnailSizeLimitBytes(prefs, app)

        SyncLog.info(
            app,
            TAG,
            "event=policyPrefetchStart folders=${policyFolders.size} policyRows=${policyFolders.size}",
        )

        var folderIndex = 0
        var folderJobsStarted = 0
        for (folder in policyFolders) {
            if (isStopped) {
                return@withContext Result.success()
            }
            val remote = rclone.getRemoteItemFromName(folder.remoteName) ?: continue
            val listing = rclone.getDirectoryContent(remote, folder.explorerDirectoryPath, startAtRoot)
                ?: continue
            val targets = ThumbnailPrefetchTargets.filterForHttpThumbnailPrefetch(
                listing,
                remote,
                prefs,
                sizeLimit,
                showThumbnails = true,
            )
            if (targets.isEmpty()) {
                continue
            }
            folderIndex++
            folderJobsStarted++
            SyncLog.info(
                app,
                TAG,
                "event=policyPrefetchFolderStart index=$folderIndex/${policyFolders.size} " +
                    "path=${folder.explorerDirectoryPath} targets=${targets.size}",
            )
            setForeground(
                createPolicyForegroundInfo(
                    app,
                    folderIndex = folderIndex,
                    folderCount = policyFolders.size,
                    folderDisplayPath = folder.explorerDirectoryPath,
                    thumbnailLoaded = 0,
                    thumbnailTotal = targets.size,
                ),
            )
            val outcome = ThumbnailPrefetchExecutor.prefetchFolder(
                app = app,
                remote = remote,
                directoryPath = folder.explorerDirectoryPath,
                targets = targets,
                isStopped = { isStopped },
                onProgress = { progress ->
                    setForeground(
                        createPolicyForegroundInfo(
                            app,
                            folderIndex = folderIndex,
                            folderCount = policyFolders.size,
                            folderDisplayPath = progress.directoryPath,
                            thumbnailLoaded = progress.loaded,
                            thumbnailTotal = progress.total,
                        ),
                    )
                },
            )
            SyncLog.info(
                app,
                TAG,
                "event=policyPrefetchFolderDone index=$folderIndex/${policyFolders.size} " +
                    "path=${folder.explorerDirectoryPath} loaded=${outcome.loaded}/${outcome.total} " +
                    "stoppedEarly=${outcome.stoppedEarly}",
            )
        }
        if (folderJobsStarted == 0) {
            SyncLog.info(app, TAG, "event=policyPrefetchNoTargets policyRows=${policyFolders.size}")
        } else {
            SyncLog.info(
                app,
                TAG,
                "event=policyPrefetchComplete foldersPrefetched=$folderJobsStarted " +
                    "policyRows=${policyFolders.size}",
            )
        }
        Result.success()
    }

    private fun createPolicyForegroundInfo(
        context: Context,
        folderIndex: Int,
        folderCount: Int,
        folderDisplayPath: String,
        thumbnailLoaded: Int,
        thumbnailTotal: Int,
    ): ForegroundInfo {
        NotificationUtils.createNotificationChannel(
            context,
            ThumbnailServerService.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.background_media_prep_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            context.getString(R.string.thumbnail_server_notification_channel_description),
        )
        val folderLine = context.getString(
            R.string.notification_media_prep_policy_folder_progress,
            folderIndex,
            folderCount,
            folderDisplayPath,
        )
        val thumbLine = context.getString(
            R.string.notification_media_prep_policy_thumbnail_progress,
            thumbnailLoaded,
            thumbnailTotal,
        )
        val notification = NotificationCompat.Builder(context, ThumbnailServerService.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle(context.getString(R.string.background_media_prep_notification_title))
            .setContentText("$folderLine · $thumbLine")
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
        private const val TAG = "MediaPolicyThumbPrefetch"
        const val UNIQUE_WORK_NAME = "media_policy_thumbnail_prefetch_v1"
        private const val WM_FOREGROUND_NOTIFICATION_ID = 184

        @JvmStatic
        fun enqueue(context: Context) {
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        @JvmStatic
        fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val app = context.applicationContext
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<MediaFolderPolicyThumbnailPrefetchWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request,
            )
            SyncLog.info(app, TAG, "Enqueued media policy thumbnail prefetch work policy=$policy")
        }

        /**
         * After config import when prefs (including media folder policy) have been restored.
         */
        @JvmStatic
        fun enqueueAfterImport(context: Context) {
            val app = context.applicationContext
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            if (!ThumbnailPrefetchTargets.readShowThumbnails(prefs, app)) {
                return
            }
            enqueue(app, ExistingWorkPolicy.REPLACE)
        }

        /**
         * On cold start when thumbnails are enabled and at least one policy folder requests them.
         */
        @JvmStatic
        fun enqueueOnAppStartIfNeeded(context: Context, rclone: Rclone) {
            val app = context.applicationContext
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            if (!ThumbnailPrefetchTargets.readShowThumbnails(prefs, app)) {
                return
            }
            if (MediaFolderPolicyPrefetchFolders.enumerate(rclone.remotes, prefs).isEmpty()) {
                return
            }
            enqueue(app, ExistingWorkPolicy.KEEP)
        }
    }
}
