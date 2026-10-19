package ca.pkay.rcloneexplorer.workmanager

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.preference.PreferenceManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ca.pkay.rcloneexplorer.Glide.OkHttpMediaDataSource
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import ca.pkay.rcloneexplorer.util.MediaFolderPolicyPrefetchFolders
import ca.pkay.rcloneexplorer.util.PolicyType
import ca.pkay.rcloneexplorer.util.SelectedFolderMediaCacheLayout
import ca.pkay.rcloneexplorer.util.SelectedFolderSimpleCacheProvider
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.WifiConnectivitiyUtil
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Background prefetch of Cache-flagged policy folder videos into the Media3 SimpleCache.
 *
 * Out of scope: image prefetch — the viewer's OkHttp cache is URL-keyed with no path extras,
 * so it cannot match a stable remote|path key. Future work: stable-key SimpleCache rework for images.
 */
class MediaFolderPolicyCachePrefetchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val wifiOnly = prefs.getBoolean(app.getString(R.string.pref_key_wifi_only_transfers), false)
        if (wifiOnly && WifiConnectivitiyUtil.dataConnection(app) == WifiConnectivitiyUtil.Connection.METERED) {
            return@withContext Result.success()
        }

        val rclone = Rclone(app)
        val folders = MediaFolderPolicyPrefetchFolders.enumerate(rclone.remotes, prefs, PolicyType.CACHE)
        if (folders.isEmpty()) {
            return@withContext Result.success()
        }

        val cache = SelectedFolderSimpleCacheProvider.getOrNull(app) ?: return@withContext Result.success()
        val factory = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpMediaDataSource.getClient()))

        val startAtRoot = ThumbnailPrefetchTargets.readStartAtRoot(prefs, app)
        val maxBytes = SelectedFolderMediaCacheLayout.exoMaxBytesForTotal(
            SelectedFolderMediaCacheLayout.readClampedTotalMaxBytesFromPrefs(prefs),
        )

        SyncLog.info(app, TAG, "event=policyCachePrefetchStart folders=${folders.size}")

        var folderIndex = 0
        for (folder in folders) {
            if (isStopped) {
                break
            }
            val remote = rclone.getRemoteItemFromName(folder.remoteName) ?: continue
            val listing = rclone.getDirectoryContent(remote, folder.explorerDirectoryPath, startAtRoot)
                ?: continue
            val videos = listing.filter { item ->
                !item.isDir && item.mimeType?.startsWith("video/") == true
            }
            if (videos.isEmpty()) {
                continue
            }

            folderIndex++
            SyncLog.info(
                app,
                TAG,
                "event=policyCachePrefetchFolderStart index=$folderIndex/${folders.size} " +
                    "path=${folder.explorerDirectoryPath} videos=${videos.size}",
            )
            prefetchFolderVideos(
                app = app,
                remote = remote,
                directoryPath = folder.explorerDirectoryPath,
                videos = videos,
                factory = factory,
                cache = cache,
                maxBytes = maxBytes,
            )
            SyncLog.info(
                app,
                TAG,
                "event=policyCachePrefetchFolderDone index=$folderIndex/${folders.size} " +
                    "path=${folder.explorerDirectoryPath}",
            )
        }

        SyncLog.info(app, TAG, "event=policyCachePrefetchComplete foldersTouched=$folderIndex")
        Result.success()
    }

    private suspend fun prefetchFolderVideos(
        app: Context,
        remote: RemoteItem,
        directoryPath: String,
        videos: List<FileItem>,
        factory: CacheDataSource.Factory,
        cache: Cache,
        maxBytes: Long,
    ) {
        var serveLeaseId = 0
        val auth = randomAuthToken()
        val port = allocatePort(CACHE_PORT_PREFERRED)
        try {
            serveLeaseId = ThumbnailServerManager.getInstance().acquireServeLease(app, remote, port, auth)
            if (serveLeaseId == 0) {
                SyncLog.info(app, TAG, "event=policyCachePrefetchLeaseFailed path=$directoryPath")
                return
            }
            ThumbnailServerService.startServing(app, remote, port, auth, directoryPath, true)
            if (!waitForThumbnailServerReady()) {
                SyncLog.info(app, TAG, "event=policyCachePrefetchServerTimeout path=$directoryPath")
                return
            }
            val hidden = ThumbnailPrefetchTargets.hiddenServePath(auth, remote.name)
            for (item in videos) {
                if (isStopped) {
                    break
                }
                val key = remote.name + "|" + item.path
                if (item.size > maxBytes / 2) {
                    continue
                }
                if (item.size > 0 && cache.isCached(key, 0, item.size)) {
                    continue
                }
                val url = ThumbnailPrefetchTargets.buildThumbnailHttpUrl(hidden, port, item)
                runCatching {
                    CacheWriter(
                        factory.createDataSourceForDownloading(),
                        DataSpec.Builder().setUri(Uri.parse(url)).setKey(key).build(),
                        null,
                        null,
                    ).cache()
                }
            }
        } finally {
            if (serveLeaseId != 0) {
                ThumbnailServerManager.getInstance().releaseServeLease(serveLeaseId)
            }
        }
    }

    private suspend fun waitForThumbnailServerReady(): Boolean {
        val deadline = System.currentTimeMillis() + SERVER_READY_TIMEOUT_MS
        var sawStarting = false
        while (System.currentTimeMillis() < deadline) {
            when (
                ThumbnailServerManager.getInstance().syncState
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
        } catch (_: IOException) {
            try {
                ServerSocket(0).use { socket -> return socket.localPort }
            } catch (e2: IOException) {
                throw IllegalStateException("No port available for cache prefetch", e2)
            }
        }
    }

    companion object {
        private const val TAG = "MediaPolicyCachePrefetch"
        const val UNIQUE_WORK_NAME = "media_policy_cache_prefetch_v1"
        private const val CACHE_PORT_PREFERRED = 29_181
        private const val SERVER_READY_TIMEOUT_MS = 45_000L
        private const val POLL_MS = 100L

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
            val request = OneTimeWorkRequestBuilder<MediaFolderPolicyCachePrefetchWorker>()
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                policy,
                request,
            )
            SyncLog.info(app, TAG, "Enqueued media policy cache prefetch work policy=$policy")
        }

        /**
         * On cold start when at least one Cache-flagged policy folder exists.
         * No settled stamp — CacheWriter skips already-cached spans.
         */
        @JvmStatic
        fun enqueueOnAppStartIfNeeded(context: Context, rclone: Rclone) {
            val app = context.applicationContext
            val prefs = PreferenceManager.getDefaultSharedPreferences(app)
            val folders = MediaFolderPolicyPrefetchFolders.enumerate(
                rclone.remotes,
                prefs,
                PolicyType.CACHE,
            )
            if (folders.isEmpty()) {
                return
            }
            enqueue(app, ExistingWorkPolicy.KEEP)
        }
    }
}
