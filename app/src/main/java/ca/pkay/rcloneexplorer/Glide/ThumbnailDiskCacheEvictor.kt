package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.load.Key
import com.bumptech.glide.signature.ObjectKey
import java.util.concurrent.ConcurrentHashMap

/**
 * Probe / evict / store helpers over the shared thumbnail disk cache
 * ([GlideDiskCacheHolder]); the same instance Glide reads and writes.
 */
object ThumbnailDiskCacheEvictor {

    /** Java SAM for batch disk-cache probes from [withOpenCache]. */
    fun interface CacheProbeAction {
        fun run(cache: ThumbnailDiskCache)
    }

    private const val TAG = "ThumbDiskEvictor"
    private val shownVideoDiskEntryRetained = ConcurrentHashMap.newKeySet<String>()
    private val pinnedVideoDiskEntryCleared = ConcurrentHashMap.newKeySet<String>()

    @JvmStatic
    fun store(context: Context, key: Key, jpegBytes: ByteArray) {
        if (jpegBytes.isEmpty()) {
            return
        }
        storeInternal(context, key, jpegBytes)
    }

    /**
     * Persists a reload (or direct-extract) JPEG under the single canonical epoch-0 disk key.
     * Reload frame variety is tracked in memory ([ThumbnailReloadEpoch]); stale
     * {@code thumbVideoReload|reloadN} entries are removed so each file keeps one on-disk thumbnail.
     */
    @JvmStatic
    fun storeVideoReloadJpeg(
        context: Context,
        remoteName: String,
        remoteFilePath: String,
        reloadEpoch: Int,
        jpegBytes: ByteArray,
    ) {
        if (jpegBytes.isEmpty()) {
            return
        }
        storeInternal(
            context,
            ObjectKey(ThumbnailCacheIdentity.videoDiskCacheKeyLabel(remoteName, remoteFilePath, 0)),
            jpegBytes,
        )
        evictVideoReloadEpochKeys(context, remoteName, remoteFilePath)
    }

    private fun evictVideoReloadEpochKeys(
        context: Context,
        remoteName: String,
        remoteFilePath: String,
    ) {
        val legacy = ThumbnailCacheIdentity.legacyEncodedServePath(remoteName, remoteFilePath)
        for (epoch in 1..ThumbnailCacheIdentity.MAX_RELOAD_EPOCH_DISK_PROBE) {
            evict(
                context,
                ObjectKey(ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, epoch)),
            )
        }
    }

    /**
     * Drops every Glide video entry for this file. The grid is showing the pinned JPEG instead.
     */
    @JvmStatic
    fun clearGlideEntriesForPinnedVideo(
        context: Context,
        remoteName: String,
        remoteFilePath: String,
    ) {
        val legacy = ThumbnailCacheIdentity.legacyEncodedServePath(remoteName, remoteFilePath)
        evictEveryVideoDiskLabel(context, legacy)
    }

    /** Same as [clearGlideEntriesForPinnedVideo], at most once per file per process. */
    @JvmStatic
    fun clearGlideEntriesForPinnedVideoOnce(
        context: Context,
        remoteName: String,
        remoteFilePath: String,
    ) {
        val stable = ThumbnailCacheIdentity.stableServePath(remoteName, remoteFilePath)
        if (!pinnedVideoDiskEntryCleared.add(stable)) {
            return
        }
        clearGlideEntriesForPinnedVideo(context, remoteName, remoteFilePath)
    }

    /**
     * Keeps the one video disk entry the grid will display and deletes other reload generations.
     * Runs once per file per process. A pinned file has no Glide entry; its JPEG lives beside the cache.
     */
    @JvmStatic
    fun retainOnlyShownVideoDiskEntry(context: Context, legacyStablePath: String) {
        val normalized = ThumbnailStablePath.normalize(legacyStablePath)
        if (PinnedVideoThumbnailStore.has(context, normalized)) {
            if (pinnedVideoDiskEntryCleared.add(normalized)) {
                evictEveryVideoDiskLabel(context, legacyStablePath)
            }
            return
        }
        if (!shownVideoDiskEntryRetained.add(normalized)) {
            return
        }
        removeUnshownVideoDiskEntries(context, legacyStablePath)
    }

    /**
     * Deletes every cached video frame for [legacyStablePath] except the one the grid shows.
     * A pinned file keeps its pin JPEG and drops the Glide copies. Returns how many cache entries
     * were removed. Safe to call for files that were never opened in this process.
     */
    @JvmStatic
    fun removeUnshownVideoDiskEntries(context: Context, legacyStablePath: String): Int {
        if (legacyStablePath.isEmpty()) {
            return 0
        }
        if (PinnedVideoThumbnailStore.has(context, legacyStablePath)) {
            return evictEveryVideoDiskLabel(context, legacyStablePath)
        }
        var removed = 0
        withOpenCache(context) { cache ->
            val shown = ThumbnailCacheIdentity.resolveVideoDiskCacheKeyFromLegacyPathIn(
                cache,
                legacyStablePath,
            )
            for (epoch in 0..ThumbnailCacheIdentity.MAX_RELOAD_EPOCH_DISK_PROBE) {
                val label = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(
                    legacyStablePath,
                    epoch,
                )
                if (label != shown && isCachedIn(cache, label)) {
                    evict(context, ObjectKey(label))
                    removed++
                }
            }
        }
        return removed
    }

    private fun evictEveryVideoDiskLabel(context: Context, legacyStablePath: String): Int {
        var removed = 0
        withOpenCache(context) { cache ->
            for (epoch in 0..ThumbnailCacheIdentity.MAX_RELOAD_EPOCH_DISK_PROBE) {
                val label = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(
                    legacyStablePath,
                    epoch,
                )
                if (isCachedIn(cache, label)) {
                    evict(context, ObjectKey(label))
                    removed++
                }
            }
        }
        return removed
    }

    private fun storeInternal(context: Context, key: Key, jpegBytes: ByteArray) {
        val cache = GlideDiskCacheHolder.get(context) ?: return
        try {
            cache.store(key, jpegBytes)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to store disk cache entry for key=%s", cache.safeKeyFor(key), t)
        }
    }

    @JvmStatic
    fun evict(context: Context, key: Key) {
        val cache = GlideDiskCacheHolder.get(context) ?: return
        try {
            cache.delete(key)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to evict disk cache entry for key=%s", cache.safeKeyFor(key), t)
        }
    }

    /** Fast disk-only probe for prefetch / explorer progress (no Glide load). */
    @JvmStatic
    fun isCachedByLabel(context: Context, cacheKeyLabel: String): Boolean {
        val cache = GlideDiskCacheHolder.get(context) ?: return false
        return isCachedIn(cache, cacheKeyLabel)
    }

    @JvmStatic
    fun isCachedIn(cache: ThumbnailDiskCache, cacheKeyLabel: String): Boolean =
        try {
            cache.containsLabel(cacheKeyLabel)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to read disk cache entry for key=%s", cacheKeyLabel, t)
            false
        }

    /** Runs [action] against the shared cache; the name predates the single-instance design. */
    @JvmStatic
    fun withOpenCache(context: Context, action: CacheProbeAction) {
        val cache = GlideDiskCacheHolder.get(context) ?: return
        try {
            action.run(cache)
        } catch (t: Throwable) {
            FLog.w(TAG, "Batch disk cache probe failed", t)
        }
    }
}
