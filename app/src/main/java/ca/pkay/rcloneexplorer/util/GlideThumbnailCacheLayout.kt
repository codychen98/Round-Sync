package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.io.File

/**
 * Storage layout and size constants for the Glide thumbnail disk cache.
 *
 * The cache lives under [Context.getFilesDir], not [Context.getCacheDir], so it survives
 * Android Settings -> Storage -> Clear cache. The directory name is versioned so a future
 * incompatible format change can just bump the suffix and let the old directory be evicted
 * by normal OS storage pressure.
 *
 * Mirror pattern: [SelectedFolderMediaCacheLayout].
 */
object GlideThumbnailCacheLayout {

    /** Directory name under [Context.getFilesDir] that holds all Glide thumbnail cache data. */
    const val BASE_DIR_NAME = "thumbnail_cache_v1"

    /** Maximum bytes allowed on disk for the Glide thumbnail LRU cache (500 MiB). */
    const val DEFAULT_DISK_CACHE_SIZE_BYTES: Long = 500L * 1024L * 1024L

    /**
     * Returns the cache directory given an explicit [filesDir] reference.
     * No IO is performed; the directory is not created here.
     */
    @JvmStatic
    fun glideDiskCacheDir(filesDir: File): File = File(filesDir, BASE_DIR_NAME)

    /**
     * Returns the cache directory derived from [context]'s [Context.getFilesDir].
     * No IO is performed; the directory is not created here.
     */
    @JvmStatic
    fun glideDiskCacheDir(context: Context): File = glideDiskCacheDir(context.filesDir)
}
