package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import androidx.work.WorkManager
import ca.pkay.rcloneexplorer.util.SyncLog
import ca.pkay.rcloneexplorer.workmanager.LastFolderThumbnailPrefetchWorker
import ca.pkay.rcloneexplorer.workmanager.MediaFolderPolicyThumbnailPrefetchWorker

/**
 * While the user has tapped Reload thumbnail on one file, background thumbnail work is paused
 * and only that file may load through Glide or [VideoThumbnailFetcher].
 */
object ThumbnailReloadPriority {

    private const val LOG_TAG = "ThumbReloadDbg"
    private val lock = Any()

    @Volatile
    private var exclusiveStablePath: String? = null

    @JvmStatic
    fun isExclusiveActive(): Boolean = exclusiveStablePath != null

    @JvmStatic
    fun exclusiveTargetStablePath(): String? = exclusiveStablePath

    @JvmStatic
    fun isExclusiveTarget(remoteName: String, path: String): Boolean {
        val target = exclusiveStablePath ?: return false
        val candidate = ThumbnailCacheIdentity.stableServePath(remoteName, path)
        return target == ThumbnailStablePath.normalize(candidate)
    }

    /** True when another file's thumbnail load should wait until the user reload finishes. */
    @JvmStatic
    fun shouldDeferThumbnailLoad(remoteName: String, path: String): Boolean {
        val target = exclusiveStablePath ?: return false
        return !isExclusiveTarget(remoteName, path)
    }

    @JvmStatic
    fun shouldDeferVideoFetch(stablePath: String): Boolean {
        val target = exclusiveStablePath ?: return false
        return target != ThumbnailStablePath.normalize(stablePath)
    }

  /**
     * Cancels prefetch workers and in-flight video fetchers, then pins exclusive priority on one file.
     */
    @JvmStatic
    fun beginExclusive(context: Context, stablePath: String) {
        val normalized = ThumbnailStablePath.normalize(stablePath)
        synchronized(lock) {
            exclusiveStablePath = normalized
        }
        cancelBackgroundPrefetch(context.applicationContext)
        VideoThumbnailFetcher.cancelAllExcept(normalized)
        log(context, "exclusiveReloadBegin stablePath=$normalized")
    }

    @JvmStatic
    fun endExclusive(context: Context, stablePath: String) {
        val normalized = ThumbnailStablePath.normalize(stablePath)
        synchronized(lock) {
            if (exclusiveStablePath == normalized) {
                exclusiveStablePath = null
            }
        }
        log(context, "exclusiveReloadEnd stablePath=$normalized")
    }

    @JvmStatic
    fun cancelBackgroundPrefetch(context: Context) {
        val app = context.applicationContext
        val wm = WorkManager.getInstance(app)
        wm.cancelUniqueWork(MediaFolderPolicyThumbnailPrefetchWorker.UNIQUE_WORK_NAME)
        wm.cancelUniqueWork(LastFolderThumbnailPrefetchWorker.UNIQUE_WORK_NAME)
        log(app, "exclusivePrefetchCancelled")
    }

    private fun log(context: Context, message: String) {
        SyncLog.info(context.applicationContext, LOG_TAG, "event=$message")
    }
}
