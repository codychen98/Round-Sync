package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
        return loadPrefetchCachedBitmap(
            context,
            remoteName,
            cover.path,
            cover.mimeType,
            maxEdgePx,
        )
    }

    /**
     * Synchronous decode of an already-cached pin or Glide DATA file for grid bind.
     * Avoids queuing Glide LOCAL/DATA work that leaves blank tiles under large folders.
     */
    @JvmStatic
    @JvmOverloads
    fun loadPrefetchCachedBitmap(
        context: Context,
        remoteName: String,
        path: String,
        mimeType: String?,
        maxEdgePx: Int,
        memoize: Boolean = false,
    ): Bitmap? {
        val app = context.applicationContext
        val mime = mimeType ?: return null
        if (mime.startsWith("video/")) {
            val stable = ThumbnailCacheIdentity.stableServePath(remoteName, path)
            val pinned = PinnedVideoThumbnailStore.fileIfPresent(app, stable)
            if (pinned != null) {
                return decodeFileMemoized(pinned, maxEdgePx, memoize)
            }
        }
        val cache = GlideDiskCacheHolder.get(app) ?: return null
        val label = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
            cache,
            remoteName,
            path,
            mime,
        ) ?: return null
        val file = try {
            cache.get(ObjectKey(label))
        } catch (_: Exception) {
            null
        } ?: return null
        return decodeFileMemoized(file, maxEdgePx, memoize)
    }

    private val decodedBitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8L).coerceIn(8L * 1024 * 1024, 48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private fun decodeFileMemoized(file: File, maxEdgePx: Int, memoize: Boolean): Bitmap? {
        if (!memoize) return decodeFile(file, maxEdgePx)
        val key = "${file.absolutePath}|${file.lastModified()}|${file.length()}|$maxEdgePx"
        decodedBitmapCache.get(key)?.let {
            if (!it.isRecycled) return it else decodedBitmapCache.remove(key)
        }
        val d = decodeFile(file, maxEdgePx) ?: return null
        decodedBitmapCache.put(key, d)
        return d
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
