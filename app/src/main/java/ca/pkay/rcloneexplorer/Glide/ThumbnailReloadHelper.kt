package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.os.Handler
import android.os.Looper
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.RecyclerViewAdapters.FileExplorerRecyclerViewAdapter
import com.bumptech.glide.load.engine.executor.GlideExecutor
import com.bumptech.glide.signature.ObjectKey

/**
 * Forces a fresh thumbnail load for one file row: clears disk cache, bumps video reload epoch,
 * then schedules a partial adapter rebind on the main thread.
 */
object ThumbnailReloadHelper {

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
        val mimeType = fileItem.mimeType
        if (mimeType.isNullOrEmpty()) {
            onComplete.onComplete(false)
            return
        }
        val appContext = context.applicationContext
        val remoteName = fileItem.remote.name
        val path = fileItem.path
        val stablePath = ThumbnailCacheIdentity.stableServePath(remoteName, path)
        val mainHandler = Handler(Looper.getMainLooper())

        GlideExecutor.newDiskCacheExecutor().execute {
            var success = true
            try {
                when {
                    mimeType.startsWith("image/") -> {
                        val probeUrl = ThumbnailCacheIdentity.buildCacheProbeUrl(remoteName, path)
                        ThumbnailDiskCacheEvictor.evict(appContext, HttpServeThumbnailGlideUrl(probeUrl))
                    }
                    mimeType.startsWith("video/") -> {
                        ThumbnailDiskCacheEvictor.evict(
                            appContext,
                            ObjectKey(ThumbnailCacheIdentity.videoDataCacheKey(fileItem)),
                        )
                        ThumbnailReloadEpoch.increment(stablePath)
                    }
                    else -> success = false
                }
            } catch (t: Throwable) {
                success = false
            }
            val completed = success
            mainHandler.post {
                if (completed) {
                    adapter.reloadThumbnailAt(position)
                }
                onComplete.onComplete(completed)
            }
        }
    }
}
