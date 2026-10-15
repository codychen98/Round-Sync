package ca.pkay.rcloneexplorer.Glide

import android.content.Context
import android.graphics.BitmapFactory
import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.util.ThumbRootCauseLog
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Shrinks oversized pinned video JPEG posters to [PinnedVideoThumbnailStore.MAX_EDGE_PX]
 * when a folder listing arrives. Must not run at bind time — rewriting the pin file changes
 * the Glide [com.bumptech.glide.signature.ObjectKey] (`lastModified:length`) and would
 * re-decode a visible row.
 */
object PinnedVideoThumbnailMigrator {

    data class Result(
        val stablePath: String,
        val oldBytes: Int,
        val newBytes: Int,
    )

    private val EXECUTOR: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "pinned-thumb-migrate").apply { isDaemon = true }
    }

    private val generation = AtomicLong(0L)

    /** Cancels any in-flight listing migration. */
    @JvmStatic
    fun cancel() {
        generation.incrementAndGet()
    }

    /**
     * Schedules background migration for video rows in [items] that already have a pin file
     * whose longest edge exceeds [PinnedVideoThumbnailStore.MAX_EDGE_PX].
     */
    @JvmStatic
    fun scheduleForListing(context: Context?, items: List<FileItem>?) {
        if (context == null || items == null || items.isEmpty()) {
            cancel()
            return
        }
        val app = context.applicationContext
        val root = PinnedVideoThumbnailStore.directory(app)
        val targets = ArrayList<Pair<String, String>>()
        for (item in items) {
            if (item.isDir) {
                continue
            }
            val mime = item.mimeType ?: continue
            if (!mime.startsWith("video/")) {
                continue
            }
            val remote = item.remote ?: continue
            val path = item.path ?: continue
            val stable = ThumbnailCacheIdentity.stableServePath(remote.name, path)
            if (PinnedVideoThumbnailStore.fileIfPresent(root, stable) == null) {
                continue
            }
            targets.add(stable to path)
        }
        if (targets.isEmpty()) {
            cancel()
            return
        }
        val gen = generation.incrementAndGet()
        EXECUTOR.execute {
            for ((stable, rowPath) in targets) {
                if (gen != generation.get()) {
                    return@execute
                }
                val result = migrateFileIfOversized(root, stable) ?: continue
                ThumbRootCauseLog.info(
                    app,
                    "pinMigrated",
                    "path=$rowPath oldBytes=${result.oldBytes} newBytes=${result.newBytes}",
                )
            }
        }
    }

    /**
     * Bounds-decodes [bytes] and returns max(width, height), or 0 when undecodable.
     */
    @JvmStatic
    fun longestEdgePx(bytes: ByteArray): Int {
        if (bytes.isEmpty()) {
            return 0
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) {
            return 0
        }
        return maxOf(w, h)
    }

    /**
     * If the pin at [stablePath] is larger than [PinnedVideoThumbnailStore.MAX_EDGE_PX],
     * rewrites it via [ThumbnailImageTranscoder]. Returns null when skipped or unchanged.
     */
    @JvmStatic
    fun migrateFileIfOversized(root: File, stablePath: String): Result? {
        val file = PinnedVideoThumbnailStore.fileIfPresent(root, stablePath) ?: return null
        val bytes = try {
            file.readBytes()
        } catch (_: Exception) {
            return null
        }
        val edge = longestEdgePx(bytes)
        if (edge <= 0 || edge <= PinnedVideoThumbnailStore.MAX_EDGE_PX) {
            return null
        }
        val out = ThumbnailImageTranscoder.transcode(bytes, PinnedVideoThumbnailStore.MAX_EDGE_PX)
            ?: return null
        if (out === bytes || out.isEmpty()) {
            return null
        }
        val outEdge = longestEdgePx(out)
        if (outEdge <= 0 || outEdge > PinnedVideoThumbnailStore.MAX_EDGE_PX) {
            return null
        }
        if (!PinnedVideoThumbnailStore.put(root, stablePath, out)) {
            return null
        }
        return Result(stablePath, bytes.size, out.size)
    }
}
