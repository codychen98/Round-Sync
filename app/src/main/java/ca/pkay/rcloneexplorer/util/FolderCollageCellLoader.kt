package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import ca.pkay.rcloneexplorer.Glide.GlideDiskCacheHolder
import ca.pkay.rcloneexplorer.Glide.PinnedVideoThumbnailStore
import ca.pkay.rcloneexplorer.Glide.ThumbnailCacheIdentity
import ca.pkay.rcloneexplorer.Glide.ThumbnailDiskCacheEvictor
import com.bumptech.glide.signature.ObjectKey
import java.io.File

/**
 * Loads collage cell bitmaps from **existing** pinned JPEGs or Glide disk-cache files only.
 * Does not use [ca.pkay.rcloneexplorer.Glide.VideoThumbnailLoader] (that path can evict pinned
 * video disk entries and must not run for collage probes).
 */
object FolderCollageCellLoader {

    @JvmStatic
    fun isAvailable(context: Context, remoteName: String, cover: FolderCoverResolver.Cover): Boolean {
        val app = context.applicationContext
        if (cover.mimeType.startsWith("video/")) {
            val stable = ThumbnailCacheIdentity.stableServePath(remoteName, cover.path)
            if (PinnedVideoThumbnailStore.has(app, stable)) {
                return true
            }
        }
        val label = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
            app,
            remoteName,
            cover.path,
            cover.mimeType,
        ) ?: return false
        return ThumbnailDiskCacheEvictor.isCachedByLabel(app, label)
    }

    @JvmStatic
    fun loadBitmap(
        context: Context,
        remoteName: String,
        cover: FolderCoverResolver.Cover,
        maxEdgePx: Int,
    ): Bitmap? {
        val app = context.applicationContext
        if (cover.mimeType.startsWith("video/")) {
            val stable = ThumbnailCacheIdentity.stableServePath(remoteName, cover.path)
            val pinned = PinnedVideoThumbnailStore.fileIfPresent(app, stable)
            if (pinned != null) {
                return decodeFile(pinned, maxEdgePx)
            }
        }
        val label = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
            app,
            remoteName,
            cover.path,
            cover.mimeType,
        ) ?: return null
        val file = diskCacheFile(app, label) ?: return null
        return decodeFile(file, maxEdgePx)
    }

    private fun diskCacheFile(context: Context, label: String): File? {
        val cache = GlideDiskCacheHolder.get(context) ?: return null
        return try {
            cache.get(ObjectKey(label))
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeFile(file: File, maxEdgePx: Int): Bitmap? {
        if (!file.isFile || file.length() <= 0L) {
            return null
        }
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null
            }
            val sample = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdgePx)
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(file.absolutePath, opts)
        } catch (_: Exception) {
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxEdgePx: Int): Int {
        var sample = 1
        val edge = maxOf(width, height)
        while (edge / sample > maxEdgePx * 2 && sample < 32) {
            sample *= 2
        }
        return sample
    }
}
