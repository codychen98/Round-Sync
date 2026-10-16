package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Resolves folder-tile collages from **already available** file thumbnails only
 * (pinned JPEGs or Glide disk-cache files). Never triggers video regenerate / serve fetch.
 *
 * Successful collages are kept in a **process-scoped** memory map and persisted via
 * [FolderCollageStore] so cold start / fragment recreate can bind immediately.
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
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Memory first, then durable collage JPEG. Disk hits are promoted into the process
     * cache so subsequent peeks are free.
     */
    fun peek(remoteName: String, folderPath: String): Outcome? {
        val key = cacheKey(remoteName, folderPath)
        val existing = cache[key]
        if (existing is Outcome.Found && existing.collage.isRecycled) {
            cache.remove(key)
        } else if (existing != null) {
            return existing
        }
        val fromDisk = FolderCollageStore.loadBitmap(appContext, remoteName, folderPath) ?: return null
        val found = Outcome.Found(fromDisk)
        cache[key] = found
        ThumbRootCauseLog.info(
            appContext,
            "folderCollageDiskHit",
            "rowPath=$folderPath",
        )
        return found
    }

    fun hasWarmCollage(remoteName: String, folderPath: String): Boolean {
        val e = cache[cacheKey(remoteName, folderPath)]
        if (e is Outcome.Found && !e.collage.isRecycled) return true
        return FolderCollageStore.fileIfPresent(appContext, remoteName, folderPath) != null
    }

    fun request(remote: RemoteItem, folderPath: String) {
        val key = cacheKey(remote.name, folderPath)
        val existing = cache[key]
        if (existing is Outcome.Found && !existing.collage.isRecycled) {
            return
        }
        if (existing is Outcome.Found && existing.collage.isRecycled) {
            cache.remove(key)
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
                when (outcome) {
                    is Outcome.Found -> {
                        FolderCollageStore.put(appContext, remote.name, folderPath, outcome.collage)
                        cache[key] = outcome
                        noneResolvedAtMs.remove(key)
                    }
                    is Outcome.None -> {
                        cache[key] = outcome
                        noneResolvedAtMs[key] = System.currentTimeMillis()
                    }
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

    /** Drops negative cache only so returning to a parent can rebuild after pins warm up. */
    fun clearNegativeCache() {
        val keys = cache.keys.toList()
        for (key in keys) {
            if (cache[key] is Outcome.None) {
                cache.remove(key)
                noneResolvedAtMs.remove(key)
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
        val cellSize = (FolderCollageComposer.DEFAULT_SIZE_PX * 0.42f).toInt().coerceAtLeast(64)
        val cellBitmaps = ArrayList<Bitmap>(4)
        try {
            for (cover in candidates) {
                if (cellBitmaps.size >= 4) {
                    break
                }
                if (!FolderCollageCellLoader.isAvailable(appContext, remote.name, cover)) {
                    continue
                }
                val bmp = FolderCollageCellLoader.loadBitmap(
                    appContext,
                    remote.name,
                    cover,
                    cellSize,
                ) ?: continue
                cellBitmaps.add(bmp)
            }
            if (cellBitmaps.isEmpty()) {
                ThumbRootCauseLog.info(
                    appContext,
                    "folderCollageNone",
                    "rowPath=$folderPath candidates=${candidates.size} cells=0",
                )
                return Outcome.None
            }
            val collage = FolderCollageComposer.compose(cellBitmaps)
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

    companion object {
        private const val NONE_RETRY_COOLDOWN_MS = 15_000L

        /** Process-scoped so fragment recreate does not drop successful collages. */
        private val cache = ConcurrentHashMap<String, Outcome>()
        private val noneResolvedAtMs = ConcurrentHashMap<String, Long>()
        private val inFlight = ConcurrentHashMap.newKeySet<String>()

        private val EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "folder-cover-resolve").apply { isDaemon = true }
        }

        @JvmStatic
        fun cacheKey(remoteName: String, folderPath: String): String {
            return remoteName + '\u0000' + folderPath
        }
    }
}
