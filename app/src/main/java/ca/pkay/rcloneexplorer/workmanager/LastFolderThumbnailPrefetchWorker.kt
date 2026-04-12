package ca.pkay.rcloneexplorer.workmanager

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Base64
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
import ca.pkay.rcloneexplorer.Glide.HttpServeThumbnailGlideUrl
import ca.pkay.rcloneexplorer.Glide.VideoThumbnailUrl
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import ca.pkay.rcloneexplorer.util.BackgroundMediaPrepWorkTracker
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.LastFolderSnapshotStore
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import java.io.IOException
import java.security.SecureRandom
import java.net.ServerSocket
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

        var prefetchRefIncremented = false
        var serveLeaseId = 0
        val auth = randomAuthToken()
        val port = allocatePort(THUMB_PORT_PREFERRED)
        val hidden = ThumbnailPrefetchTargets.hiddenServePath(auth, remote.name)
        try {
            BackgroundMediaPrepWorkTracker.incrementThumbnailPrefetchWork()
            prefetchRefIncremented = true
            serveLeaseId = ThumbnailServerManager.getInstance().acquireServeLease(app, remote, port, auth)
            if (serveLeaseId == 0) {
                return@withContext Result.success()
            }
            ThumbnailServerService.startServing(app, remote, port, auth, snapshot.directoryPath, true)
            if (!waitForThumbnailServerReady()) {
                FLog.w(TAG, "Prefetch: server did not become READY in time")
                return@withContext Result.success()
            }
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchUpdateProgress phase=init loaded=0 total=${targets.size} path=${snapshot.directoryPath}",
            )
            ThumbnailServerService.updateProgress(
                app,
                snapshot.directoryPath,
                0,
                targets.size,
                0,
                0,
            )
            val imageOpts = RequestOptions()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .placeholder(R.drawable.ic_file)
                .error(R.drawable.ic_file)
            var loaded = 0
            for (item in targets) {
                if (isStopped) {
                    break
                }
                val url = ThumbnailPrefetchTargets.buildThumbnailHttpUrl(hidden, port, item)
                val mime = item.mimeType ?: ""
                val isVideo = mime.startsWith("video/")
                try {
                    val req = if (isVideo) {
                        Glide.with(app).asDrawable().load(VideoThumbnailUrl(url)).apply(imageOpts)
                    } else {
                        Glide.with(app).asDrawable().load(HttpServeThumbnailGlideUrl(url)).apply(imageOpts)
                    }
                    req.submit().get(PREFETCH_ITEM_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    FLog.w(TAG, "Prefetch timeout: ${item.name}")
                } catch (_: ExecutionException) {
                    FLog.w(TAG, "Prefetch failed: ${item.name}")
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    FLog.w(TAG, "Prefetch interrupted", e)
                    break
                }
                loaded++
                SyncLog.info(
                    app,
                    "MediaPrepDbg",
                    "event=prefetchUpdateProgress phase=item loaded=$loaded total=${targets.size} path=${snapshot.directoryPath}",
                )
                ThumbnailServerService.updateProgress(
                    app,
                    snapshot.directoryPath,
                    loaded,
                    targets.size,
                    0,
                    0,
                )
            }
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchUpdateProgress phase=complete loaded=${targets.size} total=${targets.size} path=${snapshot.directoryPath}",
            )
            ThumbnailServerService.updateProgress(
                app,
                snapshot.directoryPath,
                targets.size,
                targets.size,
                0,
                0,
            )
            Result.success()
        } finally {
            if (prefetchRefIncremented) {
                BackgroundMediaPrepWorkTracker.decrementThumbnailPrefetchWork()
            }
            if (serveLeaseId != 0) {
                ThumbnailServerManager.getInstance().releaseServeLease(serveLeaseId)
            }
        }
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

    private suspend fun waitForThumbnailServerReady(): Boolean {
        val deadline = System.currentTimeMillis() + SERVER_READY_TIMEOUT_MS
        var sawStarting = false
        while (System.currentTimeMillis() < deadline) {
            when (
                ThumbnailServerManager.getInstance().getSyncState()
                    ?: ThumbnailServerManager.ServerState.STOPPED
            ) {
                ThumbnailServerManager.ServerState.READY -> return true
                ThumbnailServerManager.ServerState.FAILED -> return false
                ThumbnailServerManager.ServerState.STARTING -> {
                    sawStarting = true
                    delay(POLL_MS)
                }
                ThumbnailServerManager.ServerState.STOPPED -> {
                    if (sawStarting) {
                        return false
                    }
                    delay(POLL_MS)
                }
            }
        }
        return false
    }

    companion object {
        private const val TAG = "LastFolderThumbPrefetch"
        const val UNIQUE_WORK_NAME = "last_folder_thumbnail_prefetch_v1"
        private const val THUMB_PORT_PREFERRED = 29_180
        private const val SERVER_READY_TIMEOUT_MS = 45_000L
        private const val POLL_MS = 100L
        private const val PREFETCH_ITEM_TIMEOUT_S = 45L
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

        private fun randomAuthToken(): String {
            val random = SecureRandom()
            val values = ByteArray(16)
            random.nextBytes(values)
            return Base64.encodeToString(values, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
        }

        private fun allocatePort(preferred: Int): Int {
            try {
                ServerSocket(preferred).use { socket ->
                    socket.reuseAddress = true
                    return socket.localPort
                }
            } catch (e: IOException) {
                try {
                    ServerSocket(0).use { socket -> return socket.localPort }
                } catch (e2: IOException) {
                    throw IllegalStateException("No port available for thumbnail prefetch", e2)
                }
            }
        }
    }
}
