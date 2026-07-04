package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.disklrucache.DiskLruCache
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.cache.SafeKeyGenerator
import com.bumptech.glide.signature.ObjectKey
import java.io.FileOutputStream

/**
 * Removes a single Glide disk-cache entry from the canonical thumbnails directory.
 */
object ThumbnailDiskCacheEvictor {

    /** Java SAM for batch disk-cache probes from [withOpenCache]. */
    fun interface CacheProbeAction {
        fun run(cache: DiskLruCache)
    }

    private const val TAG = "ThumbDiskEvictor"
    private const val DISK_CACHE_VERSION = 1
    private const val DISK_CACHE_VALUE_COUNT = 1
    private const val DISK_CACHE_SIZE_BYTES = 500L * 1024L * 1024L

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
            ObjectKey(
                ThumbnailCacheIdentity.videoDiskCacheKeyLabel(
                    remoteName,
                    remoteFilePath,
                    0,
                ),
            ),
            jpegBytes,
        )
        evictVideoReloadEpochKeys(context, remoteName, remoteFilePath)
    }

    private fun evictVideoReloadEpochKeys(
        context: Context,
        remoteName: String,
        remoteFilePath: String,
    ) {
        for (epoch in 1..ThumbnailCacheIdentity.MAX_RELOAD_EPOCH_DISK_PROBE) {
            evict(
                context,
                ObjectKey(
                    ThumbnailCacheIdentity.videoDiskCacheKeyLabel(remoteName, remoteFilePath, epoch),
                ),
            )
        }
    }

    private fun storeInternal(context: Context, key: Key, jpegBytes: ByteArray) {
        val cacheDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext) ?: return
        val safeKey = SafeKeyGenerator().getSafeKey(key)
        var cache: DiskLruCache? = null
        try {
            cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES,
            )
            val editor = cache.edit(safeKey) ?: return
            FileOutputStream(editor.getFile(0)).use { out ->
                out.write(jpegBytes)
            }
            editor.commit()
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to store disk cache entry for key=%s", safeKey, t)
        } finally {
            try {
                cache?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    fun evict(context: Context, key: Key) {
        val cacheDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext) ?: return
        val safeKey = SafeKeyGenerator().getSafeKey(key)
        var cache: DiskLruCache? = null
        try {
            cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES,
            )
            cache.remove(safeKey)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to evict disk cache entry for key=%s", safeKey, t)
        } finally {
            try {
                cache?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** Fast disk-only probe for prefetch / explorer progress (no Glide load). */
    @JvmStatic
    fun isCachedByLabel(context: Context, cacheKeyLabel: String): Boolean {
        val cacheDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext) ?: return false
        var cache: DiskLruCache? = null
        return try {
            cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES,
            )
            isCachedIn(cache, cacheKeyLabel)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to probe disk cache for key=%s", cacheKeyLabel, t)
            false
        } finally {
            try {
                cache?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    @JvmStatic
    fun isCachedIn(cache: DiskLruCache, cacheKeyLabel: String): Boolean {
        val safeKey = SafeKeyGenerator().getSafeKey(ObjectKey(cacheKeyLabel))
        return try {
            cache.get(safeKey) != null
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to read disk cache entry for key=%s", cacheKeyLabel, t)
            false
        }
    }

    @JvmStatic
    fun withOpenCache(context: Context, action: CacheProbeAction) {
        val cacheDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext) ?: return
        var cache: DiskLruCache? = null
        try {
            cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES,
            )
            action.run(cache)
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to open disk cache for batch probe", t)
        } finally {
            try {
                cache?.close()
            } catch (ignored: Throwable) {
            }
        }
    }
}
