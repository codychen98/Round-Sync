package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.io.File

/**
 * Canonical cache layout for thumbnail and media-cache relocation phases.
 *
 * Preference order:
 * 1) [Context.getExternalCacheDir]
 * 2) [Context.getCacheDir]
 *
 * Directory creation is lazy and best-effort; callers receive null when creation fails.
 */
object CanonicalCachePathResolver {

    const val THUMBNAILS_DIR_NAME = "thumbnails"
    const val MEDIA_CACHE_DIR_NAME = "media_cache"

    fun cacheRootOrNull(context: Context): File? {
        val app = context.applicationContext
        val external = app.externalCacheDir
        if (external != null) {
            val ready = ensureDir(external)
            if (ready != null) {
                return ready
            }
            FLog.w("CanonicalCachePathResolver", "External cache dir unavailable; falling back to internal cache dir")
        }
        return ensureDir(app.cacheDir)
    }

    fun thumbnailsDirOrNull(context: Context): File? =
        cacheRootOrNull(context)?.let { ensureDir(File(it, THUMBNAILS_DIR_NAME)) }

    fun mediaCacheDirOrNull(context: Context): File? =
        cacheRootOrNull(context)?.let { ensureDir(File(it, MEDIA_CACHE_DIR_NAME)) }

    private fun ensureDir(dir: File): File? {
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                FLog.w("CanonicalCachePathResolver", "Failed to create cache directory: %s", dir.absolutePath)
                null
            } else if (!dir.isDirectory) {
                FLog.w("CanonicalCachePathResolver", "Cache path is not a directory: %s", dir.absolutePath)
                null
            } else {
                dir
            }
        } catch (t: Throwable) {
            FLog.e("CanonicalCachePathResolver", "Error creating cache directory: %s", t, dir.absolutePath)
            null
        }
    }
}
