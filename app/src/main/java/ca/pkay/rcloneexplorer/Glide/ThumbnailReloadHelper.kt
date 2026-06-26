package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.os.Handler
import android.os.Looper
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter
import ca.pkay.rcloneexplorer.Services.ThumbnailServerManager
import ca.pkay.rcloneexplorer.util.SyncLog
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.signature.ObjectKey
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Forces a fresh thumbnail load for one file row: clears disk cache, bumps video reload epoch,
 * then runs a direct extract (video) or adapter rebind (image).
 */
object ThumbnailReloadHelper {

    private const val LOG_TAG = "ThumbReloadDbg"
    private val userReloadExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun interface OnCompleteListener {
        fun onComplete(success: Boolean)
    }

    @JvmStatic
    fun reload(
        context: Context,
        fileItem: FileItem,
        adapter: FileExplorerRecyclerViewAdapter,
        position: Int,
        onComplete: OnCompleteListener,
    ) {
        val appContext = context.applicationContext
        val mimeType = fileItem.mimeType
        val remoteName = fileItem.remote.name
        val path = fileItem.path
        val stablePath = ThumbnailCacheIdentity.stableServePath(remoteName, path)
        val mainHandler = Handler(Looper.getMainLooper())

        log(
            appContext,
            "reloadStart path=$path mimeType=${mimeType ?: "null"} stablePath=$stablePath position=$position",
        )

        if (mimeType.isNullOrEmpty()) {
            log(appContext, "reloadAbort reason=emptyMimeType path=$path")
            onComplete.onComplete(false)
            return
        }

        ThumbnailReloadPriority.beginExclusive(appContext, stablePath)
        adapter.prepareExclusiveUserReload(position)

        GlideExecutor.newDiskCacheExecutor().execute {
            var success = true
            var detail = "ok"
            var videoEpoch = 0
            try {
                when {
                    mimeType.startsWith("image/") -> {
                        log(appContext, "reloadImageEvict path=$path")
                        val probeUrl = ThumbnailCacheIdentity.buildCacheProbeUrl(remoteName, path)
                        ThumbnailDiskCacheEvictor.evict(appContext, HttpServeThumbnailGlideUrl(probeUrl))
                    }
                    mimeType.startsWith("video/") -> {
                        val mgr = ThumbnailServerManager.getInstance()
                        val serverReady = mgr.getSyncState().name == "READY"
                        log(
                            appContext,
                            "reloadVideoPrepare path=$path mgrState=${mgr.getSyncState()} serveGen=${mgr.getServeGeneration()} serverReady=$serverReady",
                        )
                        if (!serverReady) {
                            success = false
                            detail = "thumbnailServerNotReady"
                        } else {
                            val previousEpoch = ThumbnailReloadEpoch.get(stablePath)
                            videoEpoch = ThumbnailReloadEpoch.increment(stablePath)
                            ThumbnailReloadResultCache.remove(stablePath)
                            clearVideoFailureLog(appContext, stablePath)
                            evictVideoDiskKeys(appContext, remoteName, path, previousEpoch, videoEpoch)
                            log(
                                appContext,
                                "reloadVideoEvictDone path=$path previousEpoch=$previousEpoch newEpoch=$videoEpoch",
                            )
                        }
                    }
                    else -> {
                        success = false
                        detail = "unsupportedMimeType"
                    }
                }
            } catch (t: Throwable) {
                success = false
                detail = "exception:${t.javaClass.simpleName}:${t.message ?: ""}"
                log(appContext, "reloadDiskWorkFail path=$path detail=$detail")
            }
            val diskSuccess = success
            val diskDetail = detail
            val epochForVideo = videoEpoch
            if (!diskSuccess || !mimeType.startsWith("video/")) {
                mainHandler.post {
                    finishReload(
                        appContext,
                        adapter,
                        fileItem,
                        position,
                        path,
                        diskSuccess,
                        diskDetail,
                        onComplete,
                        null,
                    )
                }
                return@execute
            }
            val thumbUrl = buildThumbUrl(stablePath)
            if (thumbUrl == null) {
                mainHandler.post {
                    finishReload(
                        appContext,
                        adapter,
                        fileItem,
                        position,
                        path,
                        false,
                        "thumbUrlUnavailable",
                        onComplete,
                        null,
                    )
                }
                return@execute
            }
            userReloadExecutor.execute {
                val jpeg = VideoThumbnailDirectExtract.extractJpegForUserReload(
                    appContext,
                    thumbUrl,
                    stablePath,
                )
                if (jpeg != null) {
                    ThumbnailReloadResultCache.put(stablePath, jpeg)
                    log(
                        appContext,
                        "reloadDirectCacheStore path=$path epoch=$epochForVideo bytes=${jpeg.size}",
                    )
                }
                mainHandler.post {
                    finishReload(
                        appContext,
                        adapter,
                        fileItem,
                        position,
                        path,
                        jpeg != null,
                        if (jpeg != null) "directExtractOk" else "directExtractFail",
                        onComplete,
                        jpeg,
                    )
                }
            }
        }
    }

    private fun finishReload(
        context: Context,
        adapter: FileExplorerRecyclerViewAdapter,
        fileItem: FileItem,
        position: Int,
        path: String,
        success: Boolean,
        detail: String,
        onComplete: OnCompleteListener,
        jpegBytes: ByteArray?,
    ) {
        val stablePath = ThumbnailCacheIdentity.stableServePath(fileItem.remote.name, fileItem.path)
        try {
        log(
            context,
            "reloadMainThread path=$path success=$success detail=$detail position=$position",
        )
        if (success && jpegBytes != null) {
            val applied = adapter.applyReloadedVideoThumbnail(position, fileItem, jpegBytes)
            log(context, "reloadDirectApply path=$path applied=$applied position=$position")
            if (!applied) {
                adapter.reloadThumbnailAt(position)
            }
        } else if (success) {
            adapter.reloadThumbnailAt(position)
            log(context, "reloadAdapterNotified path=$path position=$position")
        } else {
            ThumbnailReloadEpoch.markPreferExoDecode(stablePath)
            ThumbnailReloadEpoch.markPendingUserReload(stablePath)
            adapter.reloadThumbnailAt(position)
            log(context, "reloadGlideFallback path=$path position=$position detail=$detail")
        }
        onComplete.onComplete(success)
        } finally {
            ThumbnailReloadPriority.endExclusive(context, stablePath)
            adapter.notifyThumbnailRefreshForVisibleRange()
            log(context, "reloadVisibleRangeRefresh path=$path")
        }
    }

    private fun evictVideoDiskKeys(
        context: Context,
        remoteName: String,
        path: String,
        previousEpoch: Int,
        newEpoch: Int,
    ) {
        ThumbnailDiskCacheEvictor.evict(
            context,
            ObjectKey(ThumbnailCacheIdentity.videoDataCacheKey(remoteName, path)),
        )
        val lastEpoch = maxOf(previousEpoch, newEpoch)
        for (epoch in 1..lastEpoch) {
            ThumbnailDiskCacheEvictor.evict(
                context,
                ObjectKey(ThumbnailCacheIdentity.videoReloadDataCacheKey(remoteName, path, epoch)),
            )
        }
    }

    private fun buildThumbUrl(stablePath: String): String? {
        val base = ThumbnailServerManager.getInstance().getCurrentBaseUrlOrNull() ?: return null
        return if (stablePath.startsWith("/")) base + stablePath else "$base/$stablePath"
    }

    private fun clearVideoFailureLog(context: Context, stablePath: String) {
        val url = buildThumbUrl(stablePath) ?: return
        VideoThumbnailFetcher.clearFailureLogForUrl(url)
    }

    private fun log(context: Context, message: String) {
        SyncLog.info(context.applicationContext, LOG_TAG, "event=$message")
    }
}
