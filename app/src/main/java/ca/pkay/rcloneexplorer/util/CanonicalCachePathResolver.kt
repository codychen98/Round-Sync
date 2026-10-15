package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.io.File

/**
 * Canonical layout for thumbnail and media-cache paths.
 *
 * Thumbnails live under [Context.getFilesDir] so Android "Clear cache" cannot delete loaded
 * posters. Streamed media cache stays under the app cache root (regenerable).
 */
object CanonicalCachePathResolver {

    private const val TAG = "CanonicalCachePathResolver"

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
            FLog.w(TAG, "External cache dir unavailable; falling back to internal cache dir")
        }
        return ensureDir(app.cacheDir)
    }

    /**
     * Durable Glide thumbnail DiskLruCache directory: `{filesDir}/thumbnails`.
     * One-time copies from the legacy cache-rooted location when the durable dir is empty.
     */
    fun thumbnailsDirOrNull(context: Context): File? {
        val app = context.applicationContext
        val durable = ensureDir(File(app.filesDir, THUMBNAILS_DIR_NAME)) ?: return null
        migrateLegacyThumbnailsIfNeeded(app, durable)
        return durable
    }

    /** Legacy location used before durable filesDir migration (may be missing). */
    fun legacyThumbnailsDirOrNull(context: Context): File? {
        val root = cacheRootOrNull(context) ?: return null
        val legacy = File(root, THUMBNAILS_DIR_NAME)
        return if (legacy.isDirectory) legacy else null
    }

    fun mediaCacheDirOrNull(context: Context): File? =
        cacheRootOrNull(context)?.let { ensureDir(File(it, MEDIA_CACHE_DIR_NAME)) }

    /**
     * If [durableDir] has no committed cache files and the legacy cache thumbnails dir does,
     * copy every file into [durableDir]. Safe to call repeatedly.
     */
    fun migrateLegacyThumbnailsIfNeeded(context: Context, durableDir: File): Boolean {
        if (hasCommittedThumbnailEntries(durableDir)) {
            return false
        }
        val legacy = legacyThumbnailsDirOrNull(context) ?: return false
        if (!hasCommittedThumbnailEntries(legacy) && !directoryHasAnyFile(legacy)) {
            return false
        }
        return try {
            var copied = 0
            legacy.listFiles()?.forEach { src ->
                if (!src.isFile) {
                    return@forEach
                }
                val dest = File(durableDir, src.name)
                if (dest.exists() && dest.length() == src.length()) {
                    return@forEach
                }
                copyFileReplace(src, dest)
                copied++
            }
            FLog.i(TAG, "Migrated %d thumbnail files from %s to %s", copied, legacy.absolutePath, durableDir.absolutePath)
            copied > 0 || hasCommittedThumbnailEntries(durableDir)
        } catch (t: Throwable) {
            FLog.w(TAG, "Thumbnail migration failed", t)
            false
        }
    }

    fun hasCommittedThumbnailEntries(dir: File): Boolean {
        if (!dir.isDirectory) {
            return false
        }
        return dir.listFiles()?.any { it.isFile && it.name.endsWith(".0") } == true
    }

    private fun directoryHasAnyFile(dir: File): Boolean =
        dir.isDirectory && dir.listFiles()?.any { it.isFile } == true

    private fun copyFileReplace(src: File, dest: File) {
        src.inputStream().use { input ->
            dest.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private fun ensureDir(dir: File): File? {
        return try {
            if (!dir.exists() && !dir.mkdirs()) {
                FLog.w(TAG, "Failed to create cache directory: %s", dir.absolutePath)
                null
            } else if (!dir.isDirectory) {
                FLog.w(TAG, "Cache path is not a directory: %s", dir.absolutePath)
                null
            } else {
                dir
            }
        } catch (t: Throwable) {
            FLog.e(TAG, "Error creating cache directory: %s", t, dir.absolutePath)
            null
        }
    }
}
