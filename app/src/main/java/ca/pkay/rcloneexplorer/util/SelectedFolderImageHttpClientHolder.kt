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
        val dir = SelectedFolderMediaCacheLayout.okHttpHttpCacheDir(app)
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
        deleteTreeBestEffort(SelectedFolderMediaCacheLayout.okHttpHttpCacheDir(app))
    }

    private fun deleteTreeBestEffort(dir: File) {
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
