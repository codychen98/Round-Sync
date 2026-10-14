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
                FLog.d(
                    TAG,
                    "Thumbnail disk cache size memo hit size=%d MiB path=%s",
                    size / (1024L * 1024L),
                    path,
                )
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

    /** Bytes currently occupied by files directly under [dir] (DiskLruCache is flat). */
    @JvmStatic
    fun directoryUsageBytes(dir: File): Long =
        dir.listFiles()?.asSequence()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /** Count of committed DiskLruCache value files (`*.0`). */
    @JvmStatic
    fun committedEntryCount(dir: File): Int =
        dir.listFiles()?.count { it.isFile && it.name.endsWith(".0") } ?: 0

    /** Process-memoized max size for [dir], or null if [forDir] has not run yet for that path. */
    @JvmStatic
    fun memoizedMaxBytesOrNull(dir: File): Long? {
        val path = dir.absolutePath
        return memoized?.let { (cachedPath, size) -> if (cachedPath == path) size else null }
    }

    /** Test hook: clears process memo so the next [forDir] recomputes from disk. */
    @JvmStatic
    internal fun clearMemoForTests() {
        memoized = null
    }

    private fun computeForDir(dir: File): Long {
        return try {
            val available = StatFs(dir.absolutePath).availableBytes
            val usage = directoryUsageBytes(dir)
            val size = compute(available, usage)
            FLog.d(
                TAG,
                "Thumbnail disk cache size=%d MiB (available=%d MiB, usage=%d MiB) memoized=false",
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
}
