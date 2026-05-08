package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import com.bumptech.glide.Glide
import java.util.concurrent.Executors

/**
 * Clears on-disk and in-memory state for the Glide thumbnail disk cache.
 *
 * Does not change policy preferences or max-size preferences. Clears the thumbnail-failure
 * blacklist so that previously-failed files can be retried after a manual wipe.
 *
 * Threading contract:
 *  - [clearAsync] must be called from the main thread.
 *  - [onComplete] is always delivered on the main thread.
 *
 * Mirror pattern: [SelectedFolderMediaCacheClearance].
 */
object GlideThumbnailCacheClearance {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor = Executors.newSingleThreadExecutor()

    /**
     * Clears the Glide thumbnail cache asynchronously. Must be called from the main thread.
     *
     * Steps performed:
     * 1. [Glide.clearMemory] on the calling (main) thread.
     * 2. [Glide.clearDiskCache], best-effort recursive deletion of the cache directory, and
     *    [ThumbnailFailureBlacklist.clearAll] on a background thread.
     * 3. [onComplete] posted back to the main thread when done.
     *
     * Individual steps are wrapped with [runCatching] so a partial failure in one step does not
     * prevent the remaining steps from running.
     */
    @JvmStatic
    fun clearAsync(context: Context, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        Glide.get(appContext).clearMemory()
        ioExecutor.execute {
            runCatching { Glide.get(appContext).clearDiskCache() }
            runCatching { GlideThumbnailCacheLayout.glideDiskCacheDir(appContext).deleteRecursively() }
            runCatching {
                val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
                ThumbnailFailureBlacklist.clearAll(prefs)
            }
            mainHandler.post(onComplete)
        }
    }
}
