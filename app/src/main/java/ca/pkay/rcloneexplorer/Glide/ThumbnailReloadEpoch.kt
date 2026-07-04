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

    /**
     * Media-time position (ms) that produced the currently displayed thumbnail. Reload probe
     * ordering deprioritizes this position so a reload does not re-capture the identical frame
     * (Step 15B: epoch 0 and epoch 1 both succeeded at seekMs=2000).
     */
    private val lastSourcePositionsMs = ConcurrentHashMap<String, Long>()

    const val NO_SOURCE_POSITION = -1L

    private fun key(stablePath: String): String = ThumbnailStablePath.normalize(stablePath)

    @JvmStatic
    fun get(stablePath: String): Int = epochs[key(stablePath)] ?: 0

    @JvmStatic
    fun increment(stablePath: String): Int {
        val normalized = key(stablePath)
        val next = get(normalized) + 1
        epochs[normalized] = next
        return next
    }

    @JvmStatic
    fun getEpochForVideoUrl(url: String): Int =
        get(ThumbnailStablePath.canonicalFromServeUrl(url))

    @JvmStatic
    fun markPendingUserReload(stablePath: String) {
        pendingUserReload.add(key(stablePath))
    }

    /** Returns true once per user reload; consumed by the adapter bind path. */
    @JvmStatic
    fun consumePendingUserReload(stablePath: String): Boolean = pendingUserReload.remove(key(stablePath))

    @JvmStatic
    fun markPreferExoDecode(stablePath: String) {
        preferExoDecode.add(key(stablePath))
    }

    /** Returns true once per user reload; consumed by the video fetcher. */
    @JvmStatic
    fun consumePreferExoDecode(stablePath: String): Boolean = preferExoDecode.remove(key(stablePath))

    /** Records the media position whose frame was delivered as this file's thumbnail. */
    @JvmStatic
    fun recordSourcePositionMs(stablePath: String, positionMs: Long) {
        if (positionMs >= 0L) {
            lastSourcePositionsMs[key(stablePath)] = positionMs
        }
    }

    @JvmStatic
    fun recordSourcePositionForVideoUrl(url: String, positionMs: Long) {
        recordSourcePositionMs(ThumbnailStablePath.canonicalFromServeUrl(url), positionMs)
    }

    /** Position (ms) behind the current thumbnail, or [NO_SOURCE_POSITION] when unknown. */
    @JvmStatic
    fun getLastSourcePositionMs(stablePath: String): Long =
        lastSourcePositionsMs[key(stablePath)] ?: NO_SOURCE_POSITION

    @JvmStatic
    fun getLastSourcePositionForVideoUrl(url: String): Long =
        getLastSourcePositionMs(ThumbnailStablePath.canonicalFromServeUrl(url))
}
