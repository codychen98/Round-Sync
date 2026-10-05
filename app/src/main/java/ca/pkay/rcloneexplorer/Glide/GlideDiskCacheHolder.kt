package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import ca.pkay.rcloneexplorer.util.CanonicalCachePathResolver
import com.bumptech.glide.load.engine.cache.DiskCache
import java.io.File

/**
 * Process-wide owner of the single [ThumbnailDiskCache]. Glide obtains it through [factory];
 * app helpers obtain the same instance through [get], whichever side asks first.
 */
object GlideDiskCacheHolder {

    @Volatile
    private var instance: ThumbnailDiskCache? = null

    /** Null only when no canonical thumbnails directory is available on this device. */
    @JvmStatic
    fun get(context: Context): ThumbnailDiskCache? {
        val dir = CanonicalCachePathResolver.thumbnailsDirOrNull(context.applicationContext) ?: return null
        return getOrCreate(dir)
    }

    @JvmStatic
    fun factory(thumbnailsDir: File): DiskCache.Factory = DiskCache.Factory { getOrCreate(thumbnailsDir) }

    /**
     * The directory never changes in production; the path check only matters for test sandboxes
     * that share static state across differently rooted application contexts.
     */
    @Synchronized
    private fun getOrCreate(dir: File): ThumbnailDiskCache {
        val current = instance
        if (current != null && current.directory.absolutePath == dir.absolutePath) {
            return current
        }
        current?.close()
        return ThumbnailDiskCache(dir, ThumbnailDiskCacheSize.forDir(dir)).also { instance = it }
    }

    /** Test hook: drops the shared instance so the next caller opens a fresh cache. */
    @Synchronized
    internal fun resetForTests() {
        instance?.close()
        instance = null
    }
}
