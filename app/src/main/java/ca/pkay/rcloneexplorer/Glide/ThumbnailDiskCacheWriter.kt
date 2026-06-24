package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.FLog
import com.bumptech.glide.disklrucache.DiskLruCache
import com.bumptech.glide.load.Key
import com.bumptech.glide.load.engine.cache.SafeKeyGenerator
import java.io.IOException

/**
 * Writes a single JPEG payload into Glide's canonical thumbnails disk cache.
 */
internal object ThumbnailDiskCacheWriter {

    private const val TAG = "ThumbDiskWriter"
    private const val DISK_CACHE_VERSION = 1
    private const val DISK_CACHE_VALUE_COUNT = 1
    private const val DISK_CACHE_SIZE_BYTES = 500L * 1024L * 1024L

    fun put(context: Context, key: Key, jpegBytes: ByteArray): Boolean {
        val cacheDir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext) ?: return false
        val safeKey = SafeKeyGenerator().getSafeKey(key)
        var cache: DiskLruCache? = null
        return try {
            cache = DiskLruCache.open(
                cacheDir,
                DISK_CACHE_VERSION,
                DISK_CACHE_VALUE_COUNT,
                DISK_CACHE_SIZE_BYTES,
            )
            val editor = cache.edit(safeKey) ?: return false
            try {
                editor.newOutputStream(0).use { output ->
                    output.write(jpegBytes)
                }
                editor.commit()
            } catch (t: Throwable) {
                editor.abort()
                throw t
            }
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed to write disk cache entry for key=%s", safeKey, t)
            false
        } finally {
            try {
                cache?.close()
            } catch (ignored: IOException) {
            }
        }
    }
}
