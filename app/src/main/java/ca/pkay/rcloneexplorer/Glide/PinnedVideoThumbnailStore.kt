package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * User-chosen video poster frames, stored outside the Glide thumbnail cache so a later
 * prefetch or cache eviction cannot replace them.
 */
object PinnedVideoThumbnailStore {

    const val DIR_NAME = "pinned_video_thumbnails"
    const val MAX_EDGE_PX = 512

    fun interface PinRefreshListener {
        fun onPinned(stablePath: String)
    }

    private val pendingRefresh = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var refreshListener: PinRefreshListener? = null

    @JvmStatic
    fun directory(context: Context): File =
        File(context.applicationContext.filesDir, DIR_NAME)

    @JvmStatic
    fun setRefreshListener(listener: PinRefreshListener?) {
        refreshListener = listener
    }

    /** Paths pinned while no listener was attached. Cleared after the copy is returned. */
    @JvmStatic
    fun consumePendingRefresh(): Array<String> {
        if (pendingRefresh.isEmpty()) {
            return emptyArray()
        }
        val copy = pendingRefresh.toTypedArray()
        pendingRefresh.clear()
        return copy
    }

    @JvmStatic
    fun has(context: Context, stablePath: String): Boolean =
        fileIfPresent(directory(context), stablePath) != null

    @JvmStatic
    fun fileIfPresent(context: Context, stablePath: String): File? =
        fileIfPresent(directory(context), stablePath)

    @JvmStatic
    fun fileIfPresent(root: File, stablePath: String): File? {
        if (stablePath.isEmpty()) {
            return null
        }
        val file = fileFor(root, stablePath)
        return if (file.isFile && file.length() > 0L) file else null
    }

    @JvmStatic
    fun put(context: Context, stablePath: String, jpeg: ByteArray): Boolean =
        put(directory(context), stablePath, jpeg)

    @JvmStatic
    fun put(root: File, stablePath: String, jpeg: ByteArray): Boolean {
        if (stablePath.isEmpty() || jpeg.isEmpty()) {
            return false
        }
        if (!root.exists() && !root.mkdirs()) {
            return false
        }
        val dest = fileFor(root, stablePath)
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
        } catch (e: IOException) {
            tmp.delete()
            return false
        }
        if (!dest.isFile || dest.length() <= 0L) {
            return false
        }
        notifyPinned(ThumbnailStablePath.normalize(stablePath))
        return true
    }

    private fun notifyPinned(stablePath: String) {
        val listener = refreshListener
        if (listener != null) {
            listener.onPinned(stablePath)
        } else {
            pendingRefresh.add(stablePath)
        }
    }

    private fun fileFor(root: File, stablePath: String): File =
        File(root, fileName(stablePath))

    private fun fileName(stablePath: String): String {
        val normalized = ThumbnailStablePath.normalize(stablePath)
        val digest = MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(digest.size * 2)
        for (b in digest) {
            hex.append(String.format("%02x", b.toInt() and 0xFF))
        }
        return hex.toString() + ".jpg"
    }
}
