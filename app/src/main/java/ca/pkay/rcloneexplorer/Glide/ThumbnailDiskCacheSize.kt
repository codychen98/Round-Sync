package ca.pkay.rcloneexplorer.Glide

import android.os.StatFs
import ca.pkay.rcloneexplorer.util.FLog
import java.io.File

/**
 * Sizes the Glide thumbnail disk cache from free space and current usage.
 *
 * Every {@code DiskLruCache.open} on the thumbnails directory (Glide's own instance and
 * [ThumbnailDiskCacheEvictor]) must use the same {@code maxSize}, otherwise the instance with
 * the smaller limit trims entries the other one still expects. [forDir] therefore memoizes
 * the first value computed in this process.
 */
object ThumbnailDiskCacheSize {

    private const val TAG = "ThumbDiskCacheSize"

    const val FLOOR_BYTES: Long = 500L * 1024L * 1024L
    const val CEILING_BYTES: Long = 2L * 1024L * 1024L * 1024L

    @Volatile
    private var memoized: Pair<String, Long>? = null

    /**
     * Pure sizing rule: {@code min(CEILING, max(FLOOR, availableBytes / 4 + currentUsageBytes))}.
     * Including current usage guarantees the cap never drops below what is already on disk, so
     * opening the cache never evicts restored entries just because free space shrank.
     */
    @JvmStatic
    fun compute(availableBytes: Long, currentUsageBytes: Long): Long {
        val available = availableBytes.coerceAtLeast(0L)
        val usage = currentUsageBytes.coerceAtLeast(0L)
        val target = available / 4L + usage
        return target.coerceIn(FLOOR_BYTES, CEILING_BYTES)
    }

    /** Memoized per process: the first call for a directory fixes the value for all later callers. */
    @JvmStatic
    fun forDir(thumbnailsDir: File): Long {
        val path = thumbnailsDir.absolutePath
        memoized?.let { (cachedPath, size) ->
            if (cachedPath == path) {
                return size
            }
        }
        synchronized(this) {
            memoized?.let { (cachedPath, size) ->
                if (cachedPath == path) {
                    return size
                }
            }
            val size = computeForDir(thumbnailsDir)
            memoized = path to size
            return size
        }
    }

    private fun computeForDir(dir: File): Long {
        return try {
            val available = StatFs(dir.absolutePath).availableBytes
            val usage = directoryUsageBytes(dir)
            val size = compute(available, usage)
            FLog.d(
                TAG,
                "Thumbnail disk cache size=%d MiB (available=%d MiB, usage=%d MiB)",
                size / (1024L * 1024L),
                available / (1024L * 1024L),
                usage / (1024L * 1024L),
            )
            size
        } catch (e: Exception) {
            FLog.w(TAG, "Failed to size thumbnail disk cache, using floor", e)
            FLOOR_BYTES
        }
    }

    /** DiskLruCache keeps a flat directory, so a single-level listing is sufficient. */
    private fun directoryUsageBytes(dir: File): Long =
        dir.listFiles()?.asSequence()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
}
