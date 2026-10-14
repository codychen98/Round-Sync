package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import ca.pkay.rcloneexplorer.util.SyncLog
import com.bumptech.glide.load.engine.cache.DiskCache
import java.io.File

/**
 * Process-wide owner of the single [ThumbnailDiskCache]. Glide obtains it through [factory];
 * app helpers obtain the same instance through [get], whichever side asks first.
 */
object GlideDiskCacheHolder {

    private const val LOG_TITLE = "ThumbRestoreDbg"

    @Volatile
    private var instance: ThumbnailDiskCache? = null

    /** Null only when no canonical thumbnails directory is available on this device. */
    @JvmStatic
    fun get(context: Context): ThumbnailDiskCache? {
        val app = context.applicationContext
        val dir = CanonicalCachePathResolver.thumbnailsDirOrNull(app) ?: return null
        return getOrCreate(app, dir)
    }

    @JvmStatic
    fun factory(context: Context, thumbnailsDir: File): DiskCache.Factory =
        DiskCache.Factory { getOrCreate(context.applicationContext, thumbnailsDir) }

    /**
     * The directory never changes in production; the path check only matters for test sandboxes
     * that share static state across differently rooted application contexts.
     */
    @Synchronized
    private fun getOrCreate(context: Context, dir: File): ThumbnailDiskCache {
        val current = instance
        if (current != null && current.directory.absolutePath == dir.absolutePath) {
            return current
        }
        current?.close()
        val usageBytes = ThumbnailDiskCacheSize.directoryUsageBytes(dir)
        val entryCount = ThumbnailDiskCacheSize.committedEntryCount(dir)
        val memoBefore = ThumbnailDiskCacheSize.memoizedMaxBytesOrNull(dir)
        val size = ThumbnailDiskCacheSize.forDir(dir)
        SyncLog.info(
            context,
            LOG_TITLE,
            "event=diskCacheCreate path=${dir.absolutePath} usageMiB=${bytesToMiB(usageBytes)} " +
                "entries=$entryCount memoBeforeMiB=${memoBefore?.let { bytesToMiB(it) } ?: "none"} " +
                "maxSizeMiB=${bytesToMiB(size)} usageExceedsMax=${usageBytes > size}",
        )
        return ThumbnailDiskCache(dir, size).also { instance = it }
    }

    /** Diagnostic peek: does not create or open a cache. */
    @JvmStatic
    @Synchronized
    fun peekForDiag(): ThumbnailDiskCache? = instance

    /** Test hook: drops the shared instance so the next caller opens a fresh cache. */
    @Synchronized
    internal fun resetForTests() {
        instance?.close()
        instance = null
        ThumbnailDiskCacheSize.clearMemoForTests()
    }

    private fun bytesToMiB(bytes: Long): Long = bytes / (1024L * 1024L)
}
