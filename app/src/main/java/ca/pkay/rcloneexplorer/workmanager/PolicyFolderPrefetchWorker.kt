package ca.pkay.rcloneexplorer.workmanager

import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.app.NotificationCompat
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
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
import androidx.work.workDataOf
import ca.pkay.rcloneexplorer.Glide.HttpServeThumbnailGlideUrl
import ca.pkay.rcloneexplorer.Glide.VideoThumbnailUrl
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import ca.pkay.rcloneexplorer.util.BackgroundMediaPrepWorkTracker
import ca.pkay.rcloneexplorer.util.FLog
import ca.pkay.rcloneexplorer.util.GlideCacheChecker
import ca.pkay.rcloneexplorer.util.NotificationUtils
import ca.pkay.rcloneexplorer.util.SelectedFolderMediaCacheLayout
import ca.pkay.rcloneexplorer.util.SelectedFolderSimpleCacheProvider
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import ca.pkay.rcloneexplorer.util.partitionCachedItems
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import java.io.IOException
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Per-folder background worker for policy-driven prefetch.
 *
 * Triggered by [ca.pkay.rcloneexplorer.Settings.MediaFolderPolicyAdapter] when a folder
 * is toggled ON in Media Folder Policy (Step 10), and by the first-run migration (Step 11).
 *
 * TYPE = THUMBNAIL: starts rclone serve HTTP, prefetches thumbnails for the folder's direct
 * children via Glide (same rules as [LastFolderThumbnailPrefetchWorker]).
 *
 * TYPE = CACHE: starts rclone serve HTTP, fills [SelectedFolderSimpleCacheProvider] for the
 * folder's direct video children using [CacheWriter], subject to per-file and total quota limits.
 *
 * Wiring to UI toggle and first-run migration is deferred to Steps 10 and 11.
 */
class PolicyFolderPrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val remoteName = inputData.getString(KEY_REMOTE_NAME)
            ?: return@withContext Result.success()
        val folderPath = inputData.getString(KEY_FOLDER_PATH)
            ?: return@withContext Result.success()
        val type = inputData.getString(KEY_TYPE)
            ?: return@withContext Result.success()

        val wifiOnly = prefs.getBoolean(app.getString(R.string.pref_key_wifi_only_transfers), false)
        if (wifiOnly && WifiConnectivitiyUtil.dataConnection(app) == WifiConnectivitiyUtil.Connection.METERED) {
            return@withContext Result.success()
        }
        val rclone = Rclone(app)
        val remote = rclone.getRemoteItemFromName(remoteName)
            ?: return@withContext Result.success()
        if (remote.isRemoteType(RemoteItem.SAFW)) {
            return@withContext Result.success()
        }
        val listing = rclone.getDirectoryContent(remote, folderPath, false)
            ?: return@withContext Result.success()

        return@withContext when (type) {
            TYPE_THUMBNAIL -> doThumbnailWork(app, prefs, remote, folderPath, listing)
            TYPE_CACHE -> doCacheWork(app, prefs, remote, folderPath, listing)
            else -> Result.success()
        }
    }

    // region — THUMBNAIL path

    private suspend fun doThumbnailWork(
        app: Context,
        prefs: SharedPreferences,
        remote: RemoteItem,
        folderPath: String,
        listing: List<FileItem>,
    ): Result {
        if (!ThumbnailPrefetchTargets.readShowThumbnails(prefs, app)) {
            return Result.success()
        }
        val sizeLimit = ThumbnailPrefetchTargets.readThumbnailSizeLimitBytes(prefs, app)
        val targetsRaw = ThumbnailPrefetchTargets.filterForHttpThumbnailPrefetch(
            listing, remote, prefs, sizeLimit, showThumbnails = true,
        )
        val targets = ThumbnailPrefetchTargets.filterOutBlacklisted(targetsRaw, prefs, remote.name)
        val skippedBlacklisted = targetsRaw.size - targets.size
        if (skippedBlacklisted > 0) {
            SyncLog.info(
                app, TAG,
                "event=policyPrefetch phase=skipBlacklisted count=$skippedBlacklisted path=$folderPath",
            )
        }
        if (targets.isEmpty()) return Result.success()

        val probeHidden = ThumbnailPrefetchTargets.hiddenServePath(PROBE_AUTH, remote.name)
        val (toFetch, alreadyCached) = partitionCachedItems(targets) { item ->
            val url = ThumbnailPrefetchTargets.buildThumbnailHttpUrl(probeHidden, PROBE_PORT, item)
            val mime = item.mimeType ?: ""
            val model: Any = if (mime.startsWith("video/")) VideoThumbnailUrl(url)
            else HttpServeThumbnailGlideUrl(url)
            GlideCacheChecker.isInDataDiskCache(app, model)
        }
        SyncLog.info(
            app, TAG,
            "event=policyPrefetch phase=skipCached count=${alreadyCached.size} path=$folderPath",
        )
        if (toFetch.isEmpty()) return Result.success()

        setForeground(createForegroundInfo(app, folderPath))
        var prefetchRefIncremented = false
        var serveLeaseId = 0
        val auth = randomAuthToken()
        val port = allocatePort(THUMB_PORT_PREFERRED)
        val hidden = ThumbnailPrefetchTargets.hiddenServePath(auth, remote.name)
        return try {
            BackgroundMediaPrepWorkTracker.incrementThumbnailPrefetchWork()
            prefetchRefIncremented = true
            serveLeaseId = ThumbnailServerManager.getInstance()
                .acquireServeLease(app, remote, port, auth)
            if (serveLeaseId == 0) return Result.success()
            ThumbnailServerService.startServing(app, remote, port, auth, folderPath, true)
            if (!waitForServerReady()) {
                FLog.w(TAG, "PolicyPrefetch THUMBNAIL: server not ready path=$folderPath")
                return Result.success()
            }
            ThumbnailServerService.acceptBaseline(app, 0, toFetch.size)
            ThumbnailServerService.updateProgress(app, folderPath, 0, toFetch.size, 0, 0)
            val imageOpts = RequestOptions()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .placeholder(R.drawable.ic_file)
                .error(R.drawable.ic_file)
            var loaded = 0
            for (item in toFetch) {
                if (isStopped) break
                val url = ThumbnailPrefetchTargets.buildThumbnailHttpUrl(hidden, port, item)
                val mime = item.mimeType ?: ""
                try {
                    val req = if (mime.startsWith("video/")) {
                        Glide.with(app).asDrawable().load(VideoThumbnailUrl(url, item.size, item.modTime)).apply(imageOpts)
                    } else {
                        Glide.with(app).asDrawable()
                            .load(HttpServeThumbnailGlideUrl(url)).apply(imageOpts)
                    }
                    req.submit().get(PREFETCH_ITEM_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (_: TimeoutException) {
                    FLog.w(TAG, "PolicyPrefetch thumbnail timeout: ${item.name}")
                } catch (_: ExecutionException) {
                    FLog.w(TAG, "PolicyPrefetch thumbnail failed: ${item.name}")
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                loaded++
                ThumbnailServerService.updateProgress(app, folderPath, loaded, toFetch.size, 0, 0)
            }
            ThumbnailServerService.updateProgress(
                app, folderPath, toFetch.size, toFetch.size, 0, 0,
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

    // endregion

    // region — CACHE path

    private suspend fun doCacheWork(
        app: Context,
        prefs: SharedPreferences,
        remote: RemoteItem,
        folderPath: String,
        listing: List<FileItem>,
    ): Result {
        val simpleCache = SelectedFolderSimpleCacheProvider.getOrNull(app)
            ?: return Result.success()
        val totalMax = SelectedFolderMediaCacheLayout.readClampedTotalMaxBytesFromPrefs(prefs)
        val exoMax = SelectedFolderMediaCacheLayout.exoMaxBytesForTotal(totalMax)
        if (exoMax <= 0L) return Result.success()

        val videoItems = listing.filter { item ->
            !item.isDir && item.mimeType?.startsWith("video/") == true
        }
        if (videoItems.isEmpty()) return Result.success()

        setForeground(createForegroundInfo(app, folderPath))
        BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(true)
        val auth = randomAuthToken()
        val port = allocatePort(CACHE_PORT_PREFERRED)
        val hidden = ThumbnailPrefetchTargets.hiddenServePath(auth, remote.name)
        var serveLeaseId = 0
        return try {
            serveLeaseId = ThumbnailServerManager.getInstance()
                .acquireServeLease(app, remote, port, auth)
            if (serveLeaseId == 0) return Result.success()
            ThumbnailServerService.startServing(app, remote, port, auth, folderPath, true)
            if (!waitForServerReady()) {
                FLog.w(TAG, "PolicyPrefetch CACHE: server not ready path=$folderPath")
                return Result.success()
            }

            val cacheTotal = videoItems.size
            ThumbnailServerService.updateProgress(app, folderPath, 0, 0, 0, cacheTotal)

            val upstreamFactory = DefaultHttpDataSource.Factory()
            val cacheDataSource = CacheDataSource.Factory()
                .setCache(simpleCache)
                .setCacheKeyFactory(SelectedFolderSimpleCacheProvider.keyFactory())
                .setUpstreamDataSourceFactory(upstreamFactory)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource() as CacheDataSource

            val remainingQuota = exoMax - simpleCache.cacheSpace
            var runningBytesAdded = 0L
            var cacheLoaded = 0

            for (item in videoItems) {
                if (isStopped) break
                val fileSize = item.size
                if (fileSize > exoMax / 2L) {
                    SyncLog.info(
                        app, TAG,
                        "event=policyPrefetch phase=cacheSkipOversized" +
                            " path=${item.path} size=$fileSize exoMax=$exoMax",
                    )
                    cacheLoaded++
                    ThumbnailServerService.updateProgress(
                        app, folderPath, 0, 0, cacheLoaded, cacheTotal,
                    )
                    continue
                }
                if (runningBytesAdded + fileSize > remainingQuota) {
                    SyncLog.info(
                        app, TAG,
                        "event=policyPrefetch phase=cacheStopQuota path=${item.path}" +
                            " running=$runningBytesAdded quota=$remainingQuota",
                    )
                    break
                }
                val url = ThumbnailPrefetchTargets.buildThumbnailHttpUrl(hidden, port, item)
                val dataSpec = DataSpec(Uri.parse(url))
                try {
                    CacheWriter(cacheDataSource, dataSpec, null, null).cache()
                    runningBytesAdded += fileSize
                } catch (e: IOException) {
                    FLog.w(TAG, "PolicyPrefetch cache write failed: ${item.name}", e)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                cacheLoaded++
                ThumbnailServerService.updateProgress(
                    app, folderPath, 0, 0, cacheLoaded, cacheTotal,
                )
            }
            ThumbnailServerService.updateProgress(app, folderPath, 0, 0, cacheTotal, cacheTotal)
            Result.success()
        } finally {
            BackgroundMediaPrepWorkTracker.setCacheWorkInProgress(false)
            if (serveLeaseId != 0) {
                ThumbnailServerManager.getInstance().releaseServeLease(serveLeaseId)
            }
        }
    }

    // endregion

    // region — Shared helpers

    private fun createForegroundInfo(context: Context, folderDisplayPath: String): ForegroundInfo {
        NotificationUtils.createNotificationChannel(
            context,
            ThumbnailServerService.NOTIFICATION_CHANNEL_ID,
            context.getString(R.string.background_media_prep_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
            context.getString(R.string.thumbnail_server_notification_channel_description),
        )
        val folderLine = context.getString(
            R.string.notification_media_prep_folder_line, folderDisplayPath,
        )
        val notification = NotificationCompat.Builder(
            context, ThumbnailServerService.NOTIFICATION_CHANNEL_ID,
        )
            .setSmallIcon(R.drawable.ic_streaming)
            .setContentTitle(
                context.getString(R.string.background_media_prep_notification_title),
            )
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

    private suspend fun waitForServerReady(): Boolean {
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
                    if (sawStarting) return false
                    delay(POLL_MS)
                }
            }
        }
        return false
    }

    // endregion

    companion object {
        private const val TAG = "PolicyFolderPrefetch"

        // WorkData keys
        const val KEY_REMOTE_NAME = "policy_remote_name"
        const val KEY_FOLDER_PATH = "policy_folder_path"
        const val KEY_TYPE = "policy_type"

        // Type constants
        const val TYPE_THUMBNAIL = "THUMBNAIL"
        const val TYPE_CACHE = "CACHE"

        private const val THUMB_PORT_PREFERRED = 29_181
        private const val CACHE_PORT_PREFERRED = 29_182
        private const val SERVER_READY_TIMEOUT_MS = 45_000L
        private const val POLL_MS = 100L
        private const val PREFETCH_ITEM_TIMEOUT_S = 45L
        private const val WM_FOREGROUND_NOTIFICATION_ID = 184

        // Stable dummy auth used for Glide cache-key probes only (auth segment is stripped by
        // RemotePathCacheKeyFactory so any value produces the same stable cache key).
        private const val PROBE_AUTH = "probe"
        private const val PROBE_PORT = 1

        private val HEX_DIGITS = "0123456789abcdef".toCharArray()

        /**
         * Enqueues a THUMBNAIL prefetch for [folderPath] on [remoteName].
         * [ExistingWorkPolicy.REPLACE] ensures toggle-spam cancels the prior run.
         */
        @JvmStatic
        fun enqueueThumbnail(context: Context, remoteName: String, folderPath: String) {
            enqueue(context, remoteName, folderPath, TYPE_THUMBNAIL)
        }

        /**
         * Enqueues a CACHE fill for [folderPath] on [remoteName].
         * [ExistingWorkPolicy.REPLACE] ensures toggle-spam cancels the prior run.
         */
        @JvmStatic
        fun enqueueCache(context: Context, remoteName: String, folderPath: String) {
            enqueue(context, remoteName, folderPath, TYPE_CACHE)
        }

        /**
         * Cancels any in-flight work for the given (type, remote, folder) triple.
         * Called when the user toggles OFF a folder in Media Folder Policy (Step 10).
         */
        @JvmStatic
        fun cancelWork(context: Context, remoteName: String, folderPath: String, type: String) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(uniqueWorkName(type, remoteName, folderPath))
        }

        /** Stable unique-work name: predictable cancellation from Step 10. */
        fun uniqueWorkName(type: String, remoteName: String, folderPath: String): String {
            val prefix = if (type == TYPE_CACHE) "policy_prefetch_cache_" else "policy_prefetch_thumb_"
            return "${prefix}${hexEncode(remoteName)}_${hexEncode(folderPath)}"
        }

        private fun enqueue(
            context: Context,
            remoteName: String,
            folderPath: String,
            type: String,
        ) {
            val app = context.applicationContext
            val uniqueName = uniqueWorkName(type, remoteName, folderPath)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val inputData = workDataOf(
                KEY_REMOTE_NAME to remoteName,
                KEY_FOLDER_PATH to folderPath,
                KEY_TYPE to type,
            )
            val request = OneTimeWorkRequestBuilder<PolicyFolderPrefetchWorker>()
                .setConstraints(constraints)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setInputData(inputData)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                uniqueName,
                ExistingWorkPolicy.REPLACE,
                request,
            )
            SyncLog.info(
                app, TAG,
                "Enqueued policy prefetch type=$type remote=$remoteName folder=$folderPath",
            )
        }

        private fun hexEncode(value: String): String {
            val bytes = value.toByteArray(StandardCharsets.UTF_8)
            return buildString(bytes.size * 2) {
                for (b in bytes) {
                    val v = b.toInt() and 0xff
                    append(HEX_DIGITS[v ushr 4])
                    append(HEX_DIGITS[v and 0x0f])
                }
            }
        }

        private fun randomAuthToken(): String {
            val random = SecureRandom()
            val values = ByteArray(16)
            random.nextBytes(values)
            return Base64.encodeToString(values, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
        }

        private fun allocatePort(preferred: Int): Int {
            try {
                ServerSocket(preferred).use { return it.localPort }
            } catch (_: IOException) {
                try {
                    ServerSocket(0).use { return it.localPort }
                } catch (e: IOException) {
                    throw IllegalStateException("No port available for policy prefetch", e)
                }
            }
        }
    }
}
