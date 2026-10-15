package ca.pkay.rcloneexplorer.Glide

import ca.pkay.rcloneexplorer.util.FLog
import java.io.File

/**
 * Sizes the Glide thumbnail DiskLruCache.
 *
 * Loaded thumbnails are durable user data: size-based LRU eviction is disabled so Clear cache /
 * free-space pressure cannot drop existing posters. Entries are removed only via
 * [ca.pkay.rcloneexplorer.util.ThumbnailLifecycle] (delete / listing-gone / pin replace / carry).
 *
 * Every [com.bumptech.glide.disklrucache.DiskLruCache.open] on the thumbnails directory must use
 * the same [maxSize] ([forDir] memoizes per process).
 */
object ThumbnailDiskCacheSize {

    private const val TAG = "ThumbDiskCacheSize"

    /** Historical floor/ceiling kept for [compute] unit tests / docs only. */
    const val FLOOR_BYTES: Long = 500L * 1024L * 1024L
    const val CEILING_BYTES: Long = 2L * 1024L * 1024L * 1024L

    /**
     * Effectively unlimited for DiskLruCache size checks (`size > maxSize` never trips in practice).
     * Avoid [Long.MAX_VALUE] in case of overflow when adding entry lengths.
     */
    const val NO_SIZE_EVICTION_BYTES: Long = Long.MAX_VALUE / 2L

    @Volatile
    private var memoized: Pair<String, Long>? = null

    /**
     * Legacy free-space formula (unused by [forDir] after durable-no-eviction policy).
     * Kept so existing unit tests document the old rule.
     */
    @JvmStatic
    fun compute(availableBytes: Long, currentUsageBytes: Long): Long {
        val available = availableBytes.coerceAtLeast(0L)
        val usage = currentUsageBytes.coerceAtLeast(0L)
        val target = available / 4L + usage
        return target.coerceIn(FLOOR_BYTES, CEILING_BYTES)
    }

    /** Memoized per process: durable thumbs never size-evict. */
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
            val size = NO_SIZE_EVICTION_BYTES
            memoized = path to size
            FLog.d(
                TAG,
                "Thumbnail disk cache size eviction disabled maxMiB=%d path=%s",
                size / (1024L * 1024L),
                path,
            )
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
}
