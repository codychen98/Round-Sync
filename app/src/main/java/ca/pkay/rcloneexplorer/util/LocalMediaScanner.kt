package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.media.MediaScannerConnection
import ca.pkay.rcloneexplorer.Items.SyncDirectionObject
import java.io.File
import kotlin.io.OnErrorAction

/**
 * Asks Android to index files under a local destination so Gallery / Google Photos see rclone writes
 * without a reboot. Only the given path (and its descendants) are scanned — never the whole device.
 */
object LocalMediaScanner {

    private const val TAG = "LocalMediaScanner"
    private const val SCAN_BATCH_SIZE = 500

    @JvmStatic
    fun writesToLocal(direction: Int): Boolean {
        return direction == SyncDirectionObject.SYNC_REMOTE_TO_LOCAL ||
            direction == SyncDirectionObject.COPY_REMOTE_TO_LOCAL
    }

    /**
     * Scans [localPath] if it exists. Directories are walked; only paths under that root are sent
     * to [MediaScannerConnection]. Failures are logged and never thrown to the caller.
     */
    @JvmStatic
    fun scanDestination(context: Context, localPath: String?) {
        if (localPath.isNullOrBlank()) {
            return
        }
        try {
            val root = File(localPath)
            if (!root.exists()) {
                FLog.w(TAG, "Skip media scan; path missing: %s", localPath)
                return
            }
            val paths = collectScanPaths(root)
            if (paths.isEmpty()) {
                return
            }
            val appContext = context.applicationContext
            for (batch in paths.chunked(SCAN_BATCH_SIZE)) {
                MediaScannerConnection.scanFile(
                    appContext,
                    batch.toTypedArray(),
                    null,
                    null,
                )
            }
            FLog.i(TAG, "Requested media scan for %d path(s) under %s", paths.size, localPath)
        } catch (t: Throwable) {
            FLog.e(TAG, "Media scan failed for %s", t, localPath)
        }
    }

    /**
     * Paths to hand to the media scanner: the root itself (file or directory), then every file
     * under a directory root. Never leaves [root].
     */
    internal fun collectScanPaths(root: File): List<String> {
        if (root.isFile) {
            return listOf(root.absolutePath)
        }
        if (!root.isDirectory) {
            return emptyList()
        }
        val rootPath = root.absolutePath
        val files = ArrayList<String>()
        files.add(rootPath)
        root.walkTopDown().onFail { _, _ -> OnErrorAction.SKIP_SUBTREE }.forEach { file ->
            if (file.isFile) {
                files.add(file.absolutePath)
            }
        }
        return files
    }
}
