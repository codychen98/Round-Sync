package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import ca.pkay.rcloneexplorer.Rclone
import java.util.ArrayDeque
import java.util.Locale

/**
 * Recursive video listing via repeated [Rclone.getDirectoryContent] (no recursive lsjson API).
 */
object VideoProblemLister {

    internal val videoExtensions: Set<String> = setOf(
        "mkv", "mp4", "webm", "avi", "mov", "ts", "m2ts", "wmv", "flv",
    )

    data class ListedVideo(
        val item: FileItem,
        val relativePath: String,
    )

    /**
     * BFS from [rootPath]. Relative paths are relative to [rootPath] (empty root → full item path).
     * Returns null if the root listing fails.
     */
    fun listVideosRecursive(
        rclone: Rclone,
        remote: RemoteItem,
        rootPath: String,
        startAtRoot: Boolean,
        isStopped: () -> Boolean = { false },
    ): List<ListedVideo>? {
        val normalizedRoot = normalizeDir(rootPath)
        val queue = ArrayDeque<String>()
        queue.add(normalizedRoot)
        val out = ArrayList<ListedVideo>()
        var rootListed = false
        while (queue.isNotEmpty()) {
            if (isStopped()) {
                break
            }
            val dir = queue.removeFirst()
            val listing = rclone.getDirectoryContent(remote, dir, startAtRoot)
            if (listing == null) {
                if (!rootListed && dir == normalizedRoot) {
                    return null
                }
                continue
            }
            if (dir == normalizedRoot) {
                rootListed = true
            }
            for (item in listing) {
                if (isStopped()) {
                    break
                }
                if (item.isDir) {
                    val child = item.path ?: continue
                    queue.add(normalizeDir(child))
                    continue
                }
                if (!isVideoFile(item)) {
                    continue
                }
                val absolute = item.path ?: continue
                out.add(
                    ListedVideo(
                        item = item,
                        relativePath = toRelativePath(normalizedRoot, absolute),
                    ),
                )
            }
        }
        return if (!rootListed) null else out
    }

    fun isVideoFile(item: FileItem): Boolean {
        val mime = item.mimeType
        if (mime != null && mime.startsWith("video/")) {
            return true
        }
        val name = item.name ?: return false
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) {
            return false
        }
        val ext = name.substring(dot + 1).lowercase(Locale.US)
        return videoExtensions.contains(ext)
    }

    fun toRelativePath(rootPath: String, absolutePath: String): String {
        val root = normalizeDir(rootPath)
        val abs = absolutePath.trim().trimEnd('/')
        if (root.isEmpty() || root == "/") {
            return abs.trimStart('/')
        }
        if (abs == root) {
            return abs.substringAfterLast('/').ifEmpty { abs }
        }
        val prefix = "$root/"
        return if (abs.startsWith(prefix)) {
            abs.substring(prefix.length)
        } else {
            abs.trimStart('/')
        }
    }

    private fun normalizeDir(path: String): String {
        var p = path.trim()
        if (p == "/" || p.isEmpty()) {
            return ""
        }
        while (p.endsWith("/")) {
            p = p.dropLast(1)
        }
        return p
    }
}
