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
        val dir = resolveExoCacheDirWithSoftMigration(app) ?: return null
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
        val mediaRoot = CanonicalCachePathResolver.mediaCacheDirOrNull(app)
        val exoDir = mediaRoot?.let { File(it, SelectedFolderMediaCacheLayout.SUBDIR_EXO_SIMPLE_CACHE) }
        if (exoDir != null) {
            deleteTreeBestEffort(exoDir)
        }
    }

    private fun resolveExoCacheDirWithSoftMigration(context: Context): File? {
        val mediaRoot = CanonicalCachePathResolver.mediaCacheDirOrNull(context) ?: return null
        val newDir = File(mediaRoot, SelectedFolderMediaCacheLayout.SUBDIR_EXO_SIMPLE_CACHE)
        if (newDir.exists()) {
            return newDir
        }
        val legacyDir = SelectedFolderMediaCacheLayout.exoSimpleCacheDir(context)
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
                FLog.w("SelectedFolderSimpleCache", "Soft migration: could not promote legacy dir %s -> %s",
                    legacyDir.absolutePath, newDir.absolutePath)
            }
        }.onFailure {
            FLog.w("SelectedFolderSimpleCache", "Soft migration: exception promoting legacy dir", it)
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
