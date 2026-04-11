package ca.pkay.rcloneexplorer.util

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.preference.PreferenceManager
import java.io.File

/**
 * Process-wide [SimpleCache] for selected-folder media (ExoPlayer). Safe to retain for the app
 * lifetime; do not release from a single activity.
 */
object SelectedFolderSimpleCacheProvider {

    @Volatile
    private var cache: SimpleCache? = null

    @Synchronized
    fun getOrNull(context: Context): SimpleCache? {
        val app = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val total = SelectedFolderMediaCacheLayout.readClampedTotalMaxBytesFromPrefs(prefs)
        val exoMax = SelectedFolderMediaCacheLayout.exoMaxBytesForTotal(total)
        if (exoMax <= 0L) {
            return null
        }
        cache?.let { return it }
        val dir: File = SelectedFolderMediaCacheLayout.exoSimpleCacheDir(app)
        if (!dir.exists() && !dir.mkdirs()) {
            return null
        }
        val provider = StandaloneDatabaseProvider(app)
        val evictor = LeastRecentlyUsedCacheEvictor(exoMax)
        val created = SimpleCache(dir, evictor, provider)
        cache = created
        return created
    }

    /**
     * Releases the process-wide [SimpleCache] if present and deletes the Exo cache directory.
     * Call from a background thread if the directory may be large. Thread-safe.
     */
    @Synchronized
    fun clearAndInvalidate(context: Context) {
        val previous = cache
        cache = null
        if (previous != null) {
            runCatching { previous.release() }
        }
        val app = context.applicationContext
        deleteTreeBestEffort(SelectedFolderMediaCacheLayout.exoSimpleCacheDir(app))
    }

    private fun deleteTreeBestEffort(dir: File) {
        runCatching {
            if (dir.exists()) {
                dir.deleteRecursively()
            }
        }
    }
}
