package ca.pkay.rcloneexplorer.util

import android.content.Context
import ca.pkay.rcloneexplorer.Glide.PinnedVideoThumbnailStore
import ca.pkay.rcloneexplorer.Glide.ThumbnailCacheIdentity
import ca.pkay.rcloneexplorer.Glide.ThumbnailDiskCacheEvictor
import ca.pkay.rcloneexplorer.Glide.ThumbnailLabelBridge
import ca.pkay.rcloneexplorer.Items.DirectoryObject
import ca.pkay.rcloneexplorer.Items.FileItem
import com.bumptech.glide.signature.ObjectKey

/**
 * Lifecycle for durable thumbnails: purge on delete / remote-gone, carry on rename/move.
 * Size-based Glide eviction is disabled; this is the only intentional removal path besides
 * pin-replace (which overwrites pinned and clears Glide via [ThumbnailDiskCacheEvictor]).
 */
object ThumbnailLifecycle {

    private const val TAG = "ThumbLifecycle"

    /** Destination remote path after [ca.pkay.rcloneexplorer.Rclone.moveTo] into [targetDir]. */
    @JvmStatic
    fun destinationPathAfterMove(remoteName: String, targetDir: String, fileName: String): String {
        return if (targetDir == "//$remoteName") {
            fileName
        } else {
            "$targetDir/$fileName"
        }
    }

    /**
     * Paths present in [previous] but not in [current] (by [FileItem.getPath]).
     */
    @JvmStatic
    fun pathsRemovedFromListing(previous: List<FileItem>?, current: List<FileItem>?): List<FileItem> {
        if (previous.isNullOrEmpty()) {
            return emptyList()
        }
        val currentPaths = current.orEmpty().mapTo(HashSet()) { it.path }
        return previous.filter { it.path !in currentPaths }
    }

    @JvmStatic
    fun purgeMissingFromListing(
        context: Context,
        previous: List<FileItem>?,
        current: List<FileItem>?,
    ): Int {
        var purged = 0
        for (item in pathsRemovedFromListing(previous, current)) {
            purged += purgeFileItem(context, item)
        }
        return purged
    }

    @JvmStatic
    fun purgeFileItem(context: Context, item: FileItem): Int {
        val remoteName = item.remote?.name ?: return 0
        return purgePath(context, remoteName, item.path, item.isDir)
    }

    /**
     * Purge Glide + pinned for [remotePath]. When [isDir] and [directoryObject] is provided,
     * also purge known children from [DirectoryObject] cache under that folder.
     */
    @JvmStatic
    @JvmOverloads
    fun purgePath(
        context: Context,
        remoteName: String,
        remotePath: String,
        isDir: Boolean = false,
        directoryObject: DirectoryObject? = null,
    ): Int {
        if (remoteName.isEmpty() || remotePath.isEmpty()) {
            return 0
        }
        var purged = purgeSinglePath(context, remoteName, remotePath)
        if (isDir && directoryObject != null) {
            for (child in directoryObject.cachedFileItemsUnder(remotePath)) {
                purged += purgeSinglePath(context, remoteName, child.path)
            }
            directoryObject.removeCachedPathsUnder(remotePath)
        }
        return purged
    }

    @JvmStatic
    fun carry(
        context: Context,
        remoteName: String,
        oldPath: String,
        newPath: String,
    ): Boolean {
        if (remoteName.isEmpty() || oldPath.isEmpty() || newPath.isEmpty() || oldPath == newPath) {
            return false
        }
        val app = context.applicationContext
        var carried = false
        carried = carryPinned(app, remoteName, oldPath, newPath) || carried
        carried = carryGlide(app, remoteName, oldPath, newPath) || carried
        purgeSinglePath(app, remoteName, oldPath)
        if (carried) {
            FLog.d(TAG, "Carried thumbnails %s -> %s on %s", oldPath, newPath, remoteName)
        }
        return carried
    }

    private fun carryPinned(
        context: Context,
        remoteName: String,
        oldPath: String,
        newPath: String,
    ): Boolean {
        val oldStable = ThumbnailCacheIdentity.stableServePath(remoteName, oldPath)
        val src = PinnedVideoThumbnailStore.fileIfPresent(context, oldStable) ?: return false
        val bytes = try {
            src.readBytes()
        } catch (t: Throwable) {
            FLog.w(TAG, "Failed reading pinned thumb for carry", t)
            return false
        }
        if (bytes.isEmpty()) {
            return false
        }
        val newStable = ThumbnailCacheIdentity.stableServePath(remoteName, newPath)
        return PinnedVideoThumbnailStore.put(context, newStable, bytes)
    }

    private fun carryGlide(
        context: Context,
        remoteName: String,
        oldPath: String,
        newPath: String,
    ): Boolean {
        var carried = false
        ThumbnailDiskCacheEvictor.withOpenCache(context) { cache ->
            val oldLabels = glideLabelsForPath(remoteName, oldPath)
            val newLabels = glideLabelsForPath(remoteName, newPath)
            // Pair by role index: same order in both lists.
            for (i in oldLabels.indices) {
                val oldLabel = oldLabels[i]
                val newLabel = newLabels.getOrNull(i) ?: continue
                val file = cache.get(ObjectKey(oldLabel)) ?: continue
                val bytes = try {
                    file.readBytes()
                } catch (_: Throwable) {
                    continue
                }
                if (bytes.isEmpty()) {
                    continue
                }
                cache.store(ObjectKey(newLabel), bytes)
                carried = true
            }
        }
        return carried
    }

    private fun purgeSinglePath(context: Context, remoteName: String, remotePath: String): Int {
        var removed = 0
        val stable = ThumbnailCacheIdentity.stableServePath(remoteName, remotePath)
        if (PinnedVideoThumbnailStore.remove(context, stable)) {
            removed++
        }
        ThumbnailDiskCacheEvictor.withOpenCache(context) { cache ->
            for (label in glideLabelsForPath(remoteName, remotePath)) {
                if (ThumbnailDiskCacheEvictor.isCachedIn(cache, label)) {
                    ThumbnailDiskCacheEvictor.evict(context, ObjectKey(label))
                    removed++
                }
            }
        }
        return removed
    }

    /**
     * Labels that may hold a thumbnail for [remotePath] (image, folder, video + reload epochs,
     * plus common legacy variants).
     */
    private fun glideLabelsForPath(remoteName: String, remotePath: String): List<String> {
        val encoded = ThumbnailCacheIdentity.legacyEncodedServePath(remoteName, remotePath)
        val decoded = ThumbnailCacheIdentity.stableServePath(remoteName, remotePath)
        val labels = ArrayList<String>(8 + ThumbnailCacheIdentity.MAX_RELOAD_EPOCH_DISK_PROBE)
        labels.add(ThumbnailCacheIdentity.fileDataCacheKey(remoteName, remotePath))
        labels.add(ThumbnailLabelBridge.fromStablePath(decoded, "thumbFile"))
        labels.add(ThumbnailLabelBridge.fromStablePath(encoded, "thumbFolder"))
        labels.add(ThumbnailLabelBridge.fromStablePath(decoded, "thumbFolder"))
        labels.add(ThumbnailCacheIdentity.videoDiskCacheKeyLabel(remoteName, remotePath, 0))
        labels.add(ThumbnailLabelBridge.fromStablePath("$decoded|thumbV2", "thumbVideo"))
        labels.add(ThumbnailLabelBridge.fromStablePath(encoded, "thumbVideo"))
        labels.add(ThumbnailLabelBridge.fromStablePath(decoded, "thumbVideo"))
        for (epoch in 1..ThumbnailCacheIdentity.MAX_RELOAD_EPOCH_DISK_PROBE) {
            labels.add(ThumbnailCacheIdentity.videoDiskCacheKeyLabel(remoteName, remotePath, epoch))
        }
        return labels.distinct()
    }
}
