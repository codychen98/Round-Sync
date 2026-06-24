package ca.pkay.rcloneexplorer.Glide

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-file in-memory reload generation for video thumbnails. Each increment rotates alternate
 * frame probe offsets and changes the Glide disk cache namespace via [ThumbnailCacheIdentity].
 */
object ThumbnailReloadEpoch {

    private val epochs = ConcurrentHashMap<String, Int>()
    /** User tapped Reload thumbnail — next bind should use IMMEDIATE Glide priority. */
    private val pendingUserReload = ConcurrentHashMap.newKeySet<String>()
    /** User reload — next fetch should try Exo/FFmpeg before MediaMetadataRetriever. */
    private val preferExoDecode = ConcurrentHashMap.newKeySet<String>()

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

    @JvmStatic
    fun markPendingUserReload(stablePath: String) {
        pendingUserReload.add(stablePath)
    }

    /** Returns true once per user reload; consumed by the adapter bind path. */
    @JvmStatic
    fun consumePendingUserReload(stablePath: String): Boolean = pendingUserReload.remove(stablePath)

    @JvmStatic
    fun markPreferExoDecode(stablePath: String) {
        preferExoDecode.add(stablePath)
    }

    /** Returns true once per user reload; consumed by the video fetcher. */
    @JvmStatic
    fun consumePreferExoDecode(stablePath: String): Boolean = preferExoDecode.remove(stablePath)
}
