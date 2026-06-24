package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.disklrucache.DiskLruCache
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.cache.SafeKeyGenerator

/**
 * Removes a single Glide disk-cache entry from the canonical thumbnails directory.
 */
internal object ThumbnailDiskCacheEvictor {

    private const val TAG = "ThumbDiskEvictor"
    private const val DISK_CACHE_VERSION = 1
    private const val DISK_CACHE_VALUE_COUNT = 1
    private const val DISK_CACHE_SIZE_BYTES = 500L * 1024L * 1024L

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
}
