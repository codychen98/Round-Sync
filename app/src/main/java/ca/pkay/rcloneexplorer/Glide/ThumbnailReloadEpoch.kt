package ca.pkay.rcloneexplorer.Glide

import java.util.Collections
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
     * Media-time positions (ms) that already produced a thumbnail for this file during reload.
     * Reload probe ordering deprioritizes all of these so cycling reloads do not revisit frames
     * the user already saw (Step 15B only tracked the last position; Step 15G tracks the full set).
     */
    private val usedSourcePositionsMs = ConcurrentHashMap<String, MutableSet<Long>>()

    /** Most recent successful source position — used for logging and backward-compatible callers. */
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
        if (positionMs < 0L) {
            return
        }
        val normalized = key(stablePath)
        lastSourcePositionsMs[normalized] = positionMs
        val used = usedSourcePositionsMs.computeIfAbsent(normalized) {
            ConcurrentHashMap.newKeySet()
        }
        used.add(positionMs)
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

    /** All source positions already used for this file's thumbnail during reload. */
    @JvmStatic
    fun getUsedSourcePositionsMs(stablePath: String): Set<Long> {
        val used = usedSourcePositionsMs[key(stablePath)] ?: return emptySet()
        return Collections.unmodifiableSet(HashSet(used))
    }

    @JvmStatic
    fun getUsedSourcePositionsForVideoUrl(url: String): Set<Long> =
        getUsedSourcePositionsMs(ThumbnailStablePath.canonicalFromServeUrl(url))
}
