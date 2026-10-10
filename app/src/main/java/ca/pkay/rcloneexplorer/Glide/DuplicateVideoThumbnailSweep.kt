package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import androidx.preference.PreferenceManager
import ca.pkay.rcloneexplorer.Rclone
import ca.pkay.rcloneexplorer.util.LastFolderSnapshotStore
import ca.pkay.rcloneexplorer.util.MediaFolderPolicyPrefetchFolders
import ca.pkay.rcloneexplorer.util.ThumbnailPrefetchTargets
import ca.pkay.rcloneexplorer.util.VisitedThumbnailFolders

/**
 * Deletes leftover reload-thumbnail copies for videos in known folders.
 * Lists each folder once. Does not open a video or change the frame that is shown.
 */
object DuplicateVideoThumbnailSweep {

    data class Result(
        val removed: Int,
        val videos: Int,
        val failedFolders: Int,
    )

    @JvmStatic
    fun run(context: Context): Result {
        val app = context.applicationContext
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        val rclone = Rclone(app)
        val remotes = rclone.remotes ?: emptyList()
        val folders = LinkedHashSet<String>()
        for (folder in MediaFolderPolicyPrefetchFolders.enumerate(remotes, prefs)) {
            folders.add(folderKey(folder.remoteName, folder.explorerDirectoryPath))
        }
        LastFolderSnapshotStore.read(prefs)?.let { snapshot ->
            folders.add(folderKey(snapshot.remoteName, snapshot.directoryPath))
        }
        for (folder in VisitedThumbnailFolders.read(app)) {
            folders.add(folderKey(folder.remoteName, folder.directoryPath))
        }

        val startAtRoot = ThumbnailPrefetchTargets.readStartAtRoot(prefs, app)
        var removed = 0
        var videos = 0
        var failedFolders = 0
        for (key in folders) {
            val tab = key.indexOf('\t')
            if (tab <= 0) {
                continue
            }
            val remoteName = key.substring(0, tab)
            val directoryPath = key.substring(tab + 1)
            val remote = rclone.getRemoteItemFromName(remoteName)
            if (remote == null) {
                failedFolders++
                continue
            }
            val listing = rclone.getDirectoryContent(remote, directoryPath, startAtRoot)
            if (listing == null) {
                failedFolders++
                continue
            }
            for (item in listing) {
                if (item.isDir) {
                    continue
                }
                val mime = item.mimeType ?: continue
                if (!mime.startsWith("video/")) {
                    continue
                }
                val path = item.path ?: continue
                videos++
                val legacy = ThumbnailCacheIdentity.legacyEncodedServePath(remoteName, path)
                removed += ThumbnailDiskCacheEvictor.removeUnshownVideoDiskEntries(app, legacy)
            }
        }
        return Result(removed = removed, videos = videos, failedFolders = failedFolders)
    }

    private fun folderKey(remoteName: String, directoryPath: String): String =
        remoteName.trim() + "\t" + directoryPath.trim()
}
