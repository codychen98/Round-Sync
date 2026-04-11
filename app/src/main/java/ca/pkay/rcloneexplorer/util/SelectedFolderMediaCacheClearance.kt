package ca.pkay.rcloneexplorer.util

import android.content.Context

/**
 * Clears on-disk state for [SelectedFolderMediaCacheLayout] (ExoPlayer SimpleCache + OkHttp HTTP cache).
 * Does not change folder policy preferences or max-size preference.
 */
object SelectedFolderMediaCacheClearance {

    @JvmStatic
    fun clearAll(context: Context) {
        val app = context.applicationContext
        SelectedFolderSimpleCacheProvider.clearAndInvalidate(app)
        SelectedFolderImageHttpClientHolder.clearAndInvalidate(app)
    }
}
