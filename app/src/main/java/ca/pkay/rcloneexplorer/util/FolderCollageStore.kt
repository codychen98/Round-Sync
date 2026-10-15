package ca.pkay.rcloneexplorer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Persists composed folder-tile collages so cold start can show the last collage
 * without waiting for an async rebuild from child pins / Glide DATA files.
 */
object FolderCollageStore {

    const val DIR_NAME = "folder_collages"
    private const val JPEG_QUALITY = 85

    @JvmStatic
    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME)

    @JvmStatic
    fun fileIfPresent(context: Context, remoteName: String, folderPath: String): File? {
        if (remoteName.isEmpty() || folderPath.isEmpty()) {
            return null
        }
        val file = fileFor(directory(context), remoteName, folderPath)
        return if (file.isFile && file.length() > 0L) file else null
    }

    @JvmStatic
    fun loadBitmap(context: Context, remoteName: String, folderPath: String): Bitmap? {
        val file = fileIfPresent(context, remoteName, folderPath) ?: return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    @JvmStatic
    fun put(context: Context, remoteName: String, folderPath: String, collage: Bitmap): Boolean {
        if (remoteName.isEmpty() || folderPath.isEmpty() || collage.isRecycled) {
            return false
        }
        val bytes = try {
            val out = ByteArrayOutputStream(64 * 1024)
            if (!collage.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                return false
            }
            out.toByteArray()
        } catch (_: Exception) {
            return false
        } catch (_: OutOfMemoryError) {
            return false
        }
        return putBytes(directory(context), remoteName, folderPath, bytes)
    }

    @JvmStatic
    fun putBytes(root: File, remoteName: String, folderPath: String, jpeg: ByteArray): Boolean {
        if (remoteName.isEmpty() || folderPath.isEmpty() || jpeg.isEmpty()) {
            return false
        }
        if (!root.exists() && !root.mkdirs()) {
            return false
        }
        val dest = fileFor(root, remoteName, folderPath)
        val tmp = File(root, dest.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out -> out.write(jpeg) }
            if (dest.exists() && !dest.delete()) {
                tmp.delete()
                return false
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
        } catch (_: IOException) {
            tmp.delete()
            return false
        }
        return dest.isFile && dest.length() > 0L
    }

    @JvmStatic
    fun remove(context: Context, remoteName: String, folderPath: String): Boolean {
        if (remoteName.isEmpty() || folderPath.isEmpty()) {
            return false
        }
        val file = fileFor(directory(context), remoteName, folderPath)
        return !file.exists() || file.delete()
    }

    private fun fileFor(root: File, remoteName: String, folderPath: String): File =
        File(root, fileName(remoteName, folderPath))

    private fun fileName(remoteName: String, folderPath: String): String {
        val key = remoteName + '\u0000' + folderPath
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return hex.toString() + ".jpg"
    }
}
