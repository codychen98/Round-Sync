package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.util.Base64
import ca.pkay.rcloneexplorer.Glide.HttpServeThumbnailGlideUrl
import ca.pkay.rcloneexplorer.Glide.ThumbnailCacheIdentity
import ca.pkay.rcloneexplorer.Glide.ThumbnailDiskCacheEvictor
import ca.pkay.rcloneexplorer.Glide.ThumbnailReloadPriority
import ca.pkay.rcloneexplorer.Glide.VideoThumbnailUrl
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
import ca.pkay.rcloneexplorer.Services.ThumbnailServerService
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import java.io.IOException
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.delay

/**
 * Shared HTTP thumbnail prefetch loop used by last-folder and media-policy workers.
 */
object ThumbnailPrefetchExecutor {

    private const val TAG = "ThumbnailPrefetchExecutor"
    const val THUMB_PORT_PREFERRED = 29_180
    private const val SERVER_READY_TIMEOUT_MS = 45_000L
    private const val EXCLUSIVE_RELOAD_WAIT_MS = 180_000L
    private const val EXCLUSIVE_RELOAD_POLL_MS = 250L
    private const val EXPLORER_SERVE_LEASE_WAIT_MS = 300_000L
    private const val POLL_MS = 100L
    const val PREFETCH_ITEM_TIMEOUT_S = 45L

    data class FolderPrefetchProgress(
        val directoryPath: String,
        val loaded: Int,
        val total: Int,
    )

    data class FolderPrefetchOutcome(
        val loaded: Int,
        val total: Int,
        val stoppedEarly: Boolean,
    )

    @JvmStatic
    fun partitionCachedTargets(
        context: Context,
        targets: List<FileItem>,
    ): Pair<Int, List<FileItem>> {
        var cachedCount = 0
        val fetchTargets = ArrayList<FileItem>(targets.size)
        ThumbnailDiskCacheEvictor.withOpenCache(context) { cache ->
            for (item in targets) {
                val label = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
                    item.remote.name,
                    item.path,
                    item.mimeType,
                )
                if (label != null && ThumbnailDiskCacheEvictor.isCachedIn(cache, label)) {
                    cachedCount++
                } else {
                    fetchTargets.add(item)
                }
            }
        }
        return cachedCount to fetchTargets
    }

    suspend fun prefetchFolder(
        app: Context,
        remote: RemoteItem,
        directoryPath: String,
        targets: List<FileItem>,
        isStopped: () -> Boolean,
        onProgress: suspend (FolderPrefetchProgress) -> Unit,
    ): FolderPrefetchOutcome {
        if (targets.isEmpty()) {
            return FolderPrefetchOutcome(loaded = 0, total = 0, stoppedEarly = false)
        }
        val (cachedCount, fetchTargets) = partitionCachedTargets(app, targets)
        SyncLog.info(
            app,
            "MediaPrepDbg",
            "event=prefetchCacheProbe cached=$cachedCount misses=${fetchTargets.size} " +
                "total=${targets.size} path=$directoryPath",
        )
        if (fetchTargets.isEmpty()) {
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchAllCached skipServer path=$directoryPath loaded=$cachedCount total=${targets.size}",
            )
            return FolderPrefetchOutcome(loaded = cachedCount, total = targets.size, stoppedEarly = false)
        }

        var prefetchRefIncremented = false
        var serveLeaseId = 0
        val auth = randomAuthToken()
        val port = allocatePort(THUMB_PORT_PREFERRED)
        val hidden = ThumbnailPrefetchTargets.hiddenServePath(auth, remote.name)
        var loaded = cachedCount
        var stoppedEarly = false
        try {
            BackgroundMediaPrepWorkTracker.incrementThumbnailPrefetchWork()
            prefetchRefIncremented = true
            waitWhileExclusiveUserReload(app, isStopped)
            if (isStopped()) {
                stoppedEarly = true
                return FolderPrefetchOutcome(loaded = loaded, total = targets.size, stoppedEarly = true)
            }
            waitWhileExplorerForegroundServeLease(app, isStopped)
            if (isStopped()) {
                stoppedEarly = true
                return FolderPrefetchOutcome(loaded = loaded, total = targets.size, stoppedEarly = true)
            }
            serveLeaseId = ThumbnailServerManager.getInstance().acquireServeLease(app, remote, port, auth)
            if (serveLeaseId == 0) {
                SyncLog.error(
                    app,
                    "MediaPrepDbg",
                    "event=prefetchLeaseFailed path=$directoryPath port=$port",
                )
                return FolderPrefetchOutcome(loaded = loaded, total = targets.size, stoppedEarly = false)
            }
            ThumbnailServerService.startServing(app, remote, port, auth, directoryPath, true)
            if (!waitForThumbnailServerReady()) {
                FLog.w(TAG, "Prefetch: server did not become READY in time")
                SyncLog.error(
                    app,
                    "MediaPrepDbg",
                    "event=prefetchServerTimeout path=$directoryPath port=$port",
                )
                return FolderPrefetchOutcome(loaded = loaded, total = targets.size, stoppedEarly = false)
            }
            onProgress(FolderPrefetchProgress(directoryPath, loaded, targets.size))
            ThumbnailServerService.updateProgress(
                app,
                directoryPath,
                loaded,
                targets.size,
                0,
                0,
            )

            val imageOpts = RequestOptions()
                .centerCrop()
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .placeholder(R.drawable.ic_file)
                .error(R.drawable.ic_file)
            for (item in fetchTargets) {
                if (isStopped()) {
                    stoppedEarly = true
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
                    stoppedEarly = true
                    break
                }
                loaded++
                onProgress(FolderPrefetchProgress(directoryPath, loaded, targets.size))
                ThumbnailServerService.updateProgress(
                    app,
                    directoryPath,
                    loaded,
                    targets.size,
                    0,
                    0,
                )
            }
            val outcome = FolderPrefetchOutcome(loaded = loaded, total = targets.size, stoppedEarly = stoppedEarly)
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchFolderDone path=$directoryPath loaded=${outcome.loaded}/${outcome.total} " +
                    "stoppedEarly=${outcome.stoppedEarly}",
            )
            return outcome
        } finally {
            if (prefetchRefIncremented) {
                BackgroundMediaPrepWorkTracker.decrementThumbnailPrefetchWork()
            }
            if (serveLeaseId != 0) {
                ThumbnailServerManager.getInstance().releaseServeLease(serveLeaseId)
            }
        }
    }

    private suspend fun waitWhileExplorerForegroundServeLease(
        app: Context,
        isStopped: () -> Boolean,
    ) {
        if (!BackgroundMediaPrepWorkTracker.hasExplorerForegroundServeLease()) {
            return
        }
        SyncLog.info(
            app,
            "MediaPrepDbg",
            "event=prefetchWaitExplorerServeLease",
        )
        val deadline = System.currentTimeMillis() + EXPLORER_SERVE_LEASE_WAIT_MS
        while (BackgroundMediaPrepWorkTracker.hasExplorerForegroundServeLease()
            && !isStopped()
            && System.currentTimeMillis() < deadline
        ) {
            delay(EXCLUSIVE_RELOAD_POLL_MS)
        }
        if (BackgroundMediaPrepWorkTracker.hasExplorerForegroundServeLease()) {
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchExplorerServeLeaseTimeout",
            )
        } else {
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchExplorerServeLeaseEnded",
            )
        }
    }

    private suspend fun waitWhileExclusiveUserReload(
        app: Context,
        isStopped: () -> Boolean,
    ) {
        if (!ThumbnailReloadPriority.isExclusiveActive()) {
            return
        }
        SyncLog.info(
            app,
            "MediaPrepDbg",
            "event=prefetchWaitExclusiveReload",
        )
        val deadline = System.currentTimeMillis() + EXCLUSIVE_RELOAD_WAIT_MS
        while (ThumbnailReloadPriority.isExclusiveActive()
            && !isStopped()
            && System.currentTimeMillis() < deadline
        ) {
            delay(EXCLUSIVE_RELOAD_POLL_MS)
        }
        if (ThumbnailReloadPriority.isExclusiveActive()) {
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchExclusiveReloadTimeout",
            )
        } else {
            SyncLog.info(
                app,
                "MediaPrepDbg",
                "event=prefetchExclusiveReloadEnded",
            )
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
