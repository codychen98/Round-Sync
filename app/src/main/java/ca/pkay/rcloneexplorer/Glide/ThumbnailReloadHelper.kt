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

/**
 * Forces a fresh thumbnail load for one file row: clears disk cache, bumps video reload epoch,
 * then schedules a partial adapter rebind on the main thread.
 */
object ThumbnailReloadHelper {

    private const val LOG_TAG = "ThumbReloadDbg"

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

        GlideExecutor.newDiskCacheExecutor().execute {
            var success = true
            var detail = "ok"
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
                            val newEpoch = ThumbnailReloadEpoch.increment(stablePath)
                            ThumbnailReloadEpoch.markPreferExoDecode(stablePath)
                            ThumbnailReloadEpoch.markPendingUserReload(stablePath)
                            clearVideoFailureLog(appContext, stablePath)
                            evictVideoDiskKeys(appContext, remoteName, path, previousEpoch, newEpoch)
                            log(
                                appContext,
                                "reloadVideoEvictDone path=$path previousEpoch=$previousEpoch newEpoch=$newEpoch preferExo=true",
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
            val completed = success
            val completionDetail = detail
            mainHandler.post {
                log(
                    appContext,
                    "reloadMainThread path=$path success=$completed detail=$completionDetail position=$position",
                )
                if (completed) {
                    adapter.reloadThumbnailAt(position)
                    log(appContext, "reloadAdapterNotified path=$path position=$position")
                }
                onComplete.onComplete(completed)
            }
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

    private fun clearVideoFailureLog(context: Context, stablePath: String) {
        val base = ThumbnailServerManager.getInstance().getCurrentBaseUrlOrNull() ?: return
        val url = if (stablePath.startsWith("/")) base + stablePath else "$base/$stablePath"
        VideoThumbnailFetcher.clearFailureLogForUrl(url)
    }

    private fun log(context: Context, message: String) {
        SyncLog.info(context.applicationContext, LOG_TAG, "event=$message")
    }
}
