package ca.pkay.rcloneexplorer.util

import android.content.Context
import java.io.File
import java.io.IOException

/**
 * Folders the file explorer has shown. The duplicate-thumbnail sweep lists these
 * in addition to thumbnail policy folders, so a folder does not have to be open.
 */
object VisitedThumbnailFolders {

    private const val FILE_NAME = "visited_thumbnail_folders.txt"
    private const val MAX_FOLDERS = 400

    data class Folder(val remoteName: String, val directoryPath: String)

    @JvmStatic
    fun remember(context: Context, remoteName: String?, directoryPath: String?) {
        val remote = remoteName?.trim().orEmpty()
        val path = directoryPath?.trim().orEmpty()
        if (remote.isEmpty() || path.isEmpty()) {
            return
        }
        val key = "$remote\t$path"
        synchronized(this) {
            val current = readKeys(context).toMutableList()
            current.remove(key)
            current.add(key)
            val trimmed = if (current.size > MAX_FOLDERS) current.takeLast(MAX_FOLDERS) else current
            writeKeys(context, trimmed)
        }
    }

    @JvmStatic
    fun read(context: Context): List<Folder> {
        return readKeys(context).mapNotNull { line ->
            val tab = line.indexOf('\t')
            if (tab <= 0 || tab >= line.length - 1) {
                null
            } else {
                Folder(line.substring(0, tab), line.substring(tab + 1))
            }
        }
    }

    private fun readKeys(context: Context): List<String> {
        val file = file(context)
        if (!file.isFile) {
            return emptyList()
        }
        return try {
            file.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (e: IOException) {
            FLog.w("VisitedThumbnailFolders", "Could not read folder list", e)
            emptyList()
        }
    }

    private fun writeKeys(context: Context, keys: List<String>) {
        try {
            file(context).writeText(keys.joinToString("\n"))
        } catch (e: IOException) {
            FLog.w("VisitedThumbnailFolders", "Could not write folder list", e)
        }
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)
}
