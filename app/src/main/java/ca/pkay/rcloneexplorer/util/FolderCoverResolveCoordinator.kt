package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import ca.pkay.rcloneexplorer.Glide.ThumbnailCacheIdentity
import ca.pkay.rcloneexplorer.Glide.ThumbnailDiskCacheEvictor
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.R
import ca.pkay.rcloneexplorer.Rclone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Resolves folder-tile collages from **already disk-cached** file thumbnails only.
 * Never issues network / video-decode work to fill missing cells.
 */
class FolderCoverResolveCoordinator(
    appContext: Context,
    private val rcloneSupplier: () -> Rclone?,
    private val startAtRootSupplier: () -> Boolean,
    private val sizeLimitBytesSupplier: () -> Long,
    private val onResolvedMainThread: OnResolved,
) {

    fun interface OnResolved {
        fun onFolderCoverResolved(remoteName: String, folderPath: String)
    }

    sealed class Outcome {
        data class Found(val collage: Bitmap) : Outcome()
        object None : Outcome()
    }

    private val appContext = appContext.applicationContext
    private val cache = ConcurrentHashMap<String, Outcome>()
    private val noneResolvedAtMs = ConcurrentHashMap<String, Long>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun peek(remoteName: String, folderPath: String): Outcome? {
        return cache[cacheKey(remoteName, folderPath)]
    }

    fun request(remote: RemoteItem, folderPath: String) {
        val key = cacheKey(remote.name, folderPath)
        val existing = cache[key]
        if (existing is Outcome.Found) {
            return
        }
        if (existing is Outcome.None) {
            val at = noneResolvedAtMs[key] ?: 0L
            if (System.currentTimeMillis() - at < NONE_RETRY_COOLDOWN_MS) {
                return
            }
            cache.remove(key)
            noneResolvedAtMs.remove(key)
        }
        if (!inFlight.add(key)) {
            return
        }
        EXECUTOR.execute {
            try {
                val outcome = resolveCollage(remote, folderPath)
                cache[key] = outcome
                if (outcome is Outcome.None) {
                    noneResolvedAtMs[key] = System.currentTimeMillis()
                } else {
                    noneResolvedAtMs.remove(key)
                }
                mainHandler.post {
                    onResolvedMainThread.onFolderCoverResolved(remote.name, folderPath)
                }
            } catch (_: Exception) {
                cache[key] = Outcome.None
                noneResolvedAtMs[key] = System.currentTimeMillis()
                mainHandler.post {
                    onResolvedMainThread.onFolderCoverResolved(remote.name, folderPath)
                }
            } finally {
                inFlight.remove(key)
            }
        }
    }

    fun clearCache() {
        cache.clear()
        noneResolvedAtMs.clear()
        inFlight.clear()
    }

    private fun resolveCollage(remote: RemoteItem, folderPath: String): Outcome {
        val rclone = rcloneSupplier() ?: return Outcome.None
        val listing = rclone.getDirectoryContent(remote, folderPath, startAtRootSupplier())
        val entries = (listing ?: emptyList()).map { item ->
            PrefetchListingEntry(
                path = item.path,
                name = item.name,
                size = item.size,
                mimeType = item.mimeType ?: "",
                isDir = item.isDir,
            )
        }
        val candidates = FolderCoverResolver.pickCollageCandidates(
            entries,
            sizeLimitBytesSupplier(),
        )
        val cachedCovers = ArrayList<FolderCoverResolver.Cover>(4)
        for (cover in candidates) {
            if (cachedCovers.size >= 4) {
                break
            }
            if (isFileThumbCached(remote.name, cover.path, cover.mimeType)) {
                cachedCovers.add(cover)
            }
        }
        if (cachedCovers.isEmpty()) {
            ThumbRootCauseLog.info(
                appContext,
                "folderCollageNone",
                "rowPath=$folderPath candidates=${candidates.size} cached=0",
            )
            return Outcome.None
        }

        val cellSize = FolderCollageComposer.DEFAULT_SIZE_PX / 2
        val cellBitmaps = ArrayList<Bitmap>(cachedCovers.size)
        try {
            for (cover in cachedCovers) {
                val bmp = loadCachedThumbBitmap(remote.name, cover, cellSize) ?: continue
                cellBitmaps.add(bmp)
            }
            if (cellBitmaps.isEmpty()) {
                ThumbRootCauseLog.info(
                    appContext,
                    "folderCollageNone",
                    "rowPath=$folderPath candidates=${candidates.size} cachedLabel=${cachedCovers.size} decode=0",
                )
                return Outcome.None
            }
            val badge: Drawable? = ContextCompat.getDrawable(appContext, R.drawable.ic_folder)
            val collage = FolderCollageComposer.compose(cellBitmaps, badge)
            ThumbRootCauseLog.info(
                appContext,
                "folderCollageReady",
                "rowPath=$folderPath cells=${cellBitmaps.size}",
            )
            return Outcome.Found(collage)
        } finally {
            for (bmp in cellBitmaps) {
                if (!bmp.isRecycled) {
                    bmp.recycle()
                }
            }
        }
    }

    private fun isFileThumbCached(remoteName: String, path: String, mimeType: String): Boolean {
        val label = ThumbnailCacheIdentity.prefetchDiskCacheKeyLabel(
            appContext,
            remoteName,
            path,
            mimeType,
        ) ?: return false
        return ThumbnailDiskCacheEvictor.isCachedByLabel(appContext, label)
    }

    private fun loadCachedThumbBitmap(
        remoteName: String,
        cover: FolderCoverResolver.Cover,
        cellSize: Int,
    ): Bitmap? {
        val model = ThumbnailCacheIdentity.buildDiskCacheLoadModel(
            remoteName,
            cover.path,
            cover.mimeType,
        ) ?: return null
        val future = Glide.with(appContext)
            .asBitmap()
            .load(model)
            .apply(
                RequestOptions()
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.DATA)
                    .centerCrop()
                    .dontAnimate(),
            )
            .submit(cellSize, cellSize)
        return try {
            future.get(3, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        } finally {
            Glide.with(appContext).clear(future)
        }
    }

    companion object {
        private const val NONE_RETRY_COOLDOWN_MS = 15_000L

        private val EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "folder-cover-resolve").apply { isDaemon = true }
        }

        @JvmStatic
        fun cacheKey(remoteName: String, folderPath: String): String {
            return remoteName + '\u0000' + folderPath
        }
    }
}
