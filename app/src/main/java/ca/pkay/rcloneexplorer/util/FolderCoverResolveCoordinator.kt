package ca.pkay.rcloneexplorer.util

import android.os.Handler
import android.os.Looper
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Async folder→cover resolution with in-memory cache. Listing runs off the main thread.
 */
class FolderCoverResolveCoordinator(
    private val rcloneSupplier: () -> Rclone?,
    private val startAtRootSupplier: () -> Boolean,
    private val sizeLimitBytesSupplier: () -> Long,
    private val onResolvedMainThread: OnResolved,
) {

    fun interface OnResolved {
        fun onFolderCoverResolved(remoteName: String, folderPath: String)
    }

    sealed class Outcome {
        data class Found(val cover: FolderCoverResolver.Cover) : Outcome()
        object None : Outcome()
    }

    private val cache = ConcurrentHashMap<String, Outcome>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Cached outcome, or null when not yet resolved (including in-flight).
     */
    fun peek(remoteName: String, folderPath: String): Outcome? {
        return cache[cacheKey(remoteName, folderPath)]
    }

    /**
     * Starts a listing if this folder is not cached and not already in flight.
     */
    fun request(remote: RemoteItem, folderPath: String) {
        val key = cacheKey(remote.name, folderPath)
        if (cache.containsKey(key)) {
            return
        }
        if (!inFlight.add(key)) {
            return
        }
        EXECUTOR.execute {
            try {
                val rclone = rcloneSupplier()
                val outcome: Outcome = if (rclone == null) {
                    Outcome.None
                } else {
                    val listing = rclone.getDirectoryContent(
                        remote,
                        folderPath,
                        startAtRootSupplier(),
                    )
                    val entries = (listing ?: emptyList()).map { item ->
                        PrefetchListingEntry(
                            path = item.path,
                            name = item.name,
                            size = item.size,
                            mimeType = item.mimeType ?: "",
                            isDir = item.isDir,
                        )
                    }
                    val cover = FolderCoverResolver.pickCover(
                        entries,
                        sizeLimitBytesSupplier(),
                    )
                    if (cover != null) {
                        Outcome.Found(cover)
                    } else {
                        Outcome.None
                    }
                }
                cache[key] = outcome
                mainHandler.post {
                    onResolvedMainThread.onFolderCoverResolved(remote.name, folderPath)
                }
            } catch (_: Exception) {
                cache[key] = Outcome.None
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
        inFlight.clear()
    }

    companion object {
        private val EXECUTOR: ExecutorService = Executors.newFixedThreadPool(2) { runnable ->
            Thread(runnable, "folder-cover-resolve").apply { isDaemon = true }
        }

        @JvmStatic
        fun cacheKey(remoteName: String, folderPath: String): String {
            return remoteName + '\u0000' + folderPath
        }
    }
}
