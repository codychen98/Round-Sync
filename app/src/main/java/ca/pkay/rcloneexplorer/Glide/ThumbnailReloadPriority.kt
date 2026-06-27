package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.SyncLog

/**
 * While the user has tapped Reload thumbnail on one file, in-flight video fetchers for other files
 * are cancelled. Direct extract on the target file runs outside Glide; list rows bind from disk
 * cache or keep their drawable until reload finishes.
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
     * Cancels in-flight video fetchers for other files, then pins exclusive priority on one file.
     */
    @JvmStatic
    fun beginExclusive(context: Context, stablePath: String) {
        val normalized = ThumbnailStablePath.normalize(stablePath)
        synchronized(lock) {
            exclusiveStablePath = normalized
        }
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

    private fun log(context: Context, message: String) {
        SyncLog.info(context.applicationContext, LOG_TAG, "event=$message")
    }
}
