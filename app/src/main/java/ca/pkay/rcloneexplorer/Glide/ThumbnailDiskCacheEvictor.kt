package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.disklrucache.DiskLruCache
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.cache.SafeKeyGenerator
import com.bumptech.glide.signature.ObjectKey

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
            editor.newOutputStream(0).use { out ->
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
