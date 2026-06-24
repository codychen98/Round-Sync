package ca.pkay.rcloneexplorer.Glide

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-file in-memory reload generation for video thumbnails. Each increment rotates alternate
 * frame probe offsets and changes the Glide disk cache namespace via [ThumbnailCacheIdentity].
 */
object ThumbnailReloadEpoch {

    private val epochs = ConcurrentHashMap<String, Int>()

    @JvmStatic
    fun get(stablePath: String): Int = epochs[stablePath] ?: 0

    @JvmStatic
    fun increment(stablePath: String): Int {
        val next = get(stablePath) + 1
        epochs[stablePath] = next
        return next
    }

    @JvmStatic
    fun getEpochForVideoUrl(url: String): Int = get(VideoThumbnailUrl.stablePathFromUrl(url))
}
