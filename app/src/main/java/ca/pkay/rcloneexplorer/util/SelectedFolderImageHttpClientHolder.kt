package ca.pkay.rcloneexplorer.util

import android.content.Context
import androidx.preference.PreferenceManager
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

/**
 * OkHttp client with disk [Cache] under [SelectedFolderMediaCacheLayout.okHttpHttpCacheDir].
 * Shared for in-app image loads that participate in selected-folder caching.
 */
object SelectedFolderImageHttpClientHolder {

    @Volatile
    private var client: OkHttpClient? = null

    @Synchronized
    fun getOrNull(context: Context): OkHttpClient? {
        val app = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val total = SelectedFolderMediaCacheLayout.readClampedTotalMaxBytesFromPrefs(prefs)
        val max = SelectedFolderMediaCacheLayout.okHttpMaxBytesForTotal(total)
        if (max <= 0L) {
            return null
        }
        client?.let { return it }
        val dir = resolveHttpCacheDirWithSoftMigration(app) ?: return null
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        val diskCache = Cache(dir, max)
        val created = OkHttpClient.Builder().cache(diskCache).build()
        client = created
        return created
    }

    /**
     * Closes the shared OkHttp disk [Cache] if present and deletes the HTTP cache directory.
     * Call from a background thread if the directory may be large. Thread-safe.
     */
    @Synchronized
    fun clearAndInvalidate(context: Context) {
        val previous = client
        client = null
        if (previous != null) {
            runCatching {
                previous.dispatcher.cancelAll()
                previous.connectionPool.evictAll()
                previous.cache?.delete()
            }
        }
        val app = context.applicationContext
        val mediaRoot = CanonicalCachePathResolver.mediaCacheDirOrNull(app)
        val httpDir = mediaRoot?.let { File(it, SelectedFolderMediaCacheLayout.SUBDIR_OKHTTP_HTTP_CACHE) }
        if (httpDir != null) {
            deleteTreeBestEffort(httpDir)
        }
    }

    private fun resolveHttpCacheDirWithSoftMigration(context: Context): File? {
        val mediaRoot = CanonicalCachePathResolver.mediaCacheDirOrNull(context) ?: return null
        val newDir = File(mediaRoot, SelectedFolderMediaCacheLayout.SUBDIR_OKHTTP_HTTP_CACHE)
        if (newDir.exists()) {
            return newDir
        }
        val legacyDir = SelectedFolderMediaCacheLayout.okHttpHttpCacheDir(context)
        if (legacyDir.exists()) {
            promoteLegacyDir(legacyDir, newDir)
        }
        return newDir
    }

    private fun promoteLegacyDir(legacyDir: File, newDir: File) {
        if (newDir.exists()) {
            return
        }
        runCatching {
            newDir.parentFile?.mkdirs()
            if (!legacyDir.renameTo(newDir)) {
                // Keep transition non-blocking: leave legacy in place if promotion fails.
                FLog.w("SelectedFolderImageCache", "Soft migration: could not promote legacy dir %s -> %s",
                    legacyDir.absolutePath, newDir.absolutePath)
            }
        }.onFailure {
            FLog.w("SelectedFolderImageCache", "Soft migration: exception promoting legacy dir", it)
        }
    }

    private fun deleteTreeBestEffort(dir: File) {
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
