package ca.pkay.rcloneexplorer.Activities

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.TextureView
import androidx.annotation.RequiresApi
import androidx.media3.ui.PlayerView
import ca.pkay.rcloneexplorer.Glide.OkHttpMediaDataSource
import ca.pkay.rcloneexplorer.Glide.PinnedVideoThumbnailStore
import ca.pkay.rcloneexplorer.Glide.VideoFrameJpegEncoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Copies the frame currently shown in the player. On API 24+ that is the video surface.
 * When the surface cannot be copied, one extract at the playback position is used instead.
 * A copied frame that is still empty is not replaced with a different picture.
 */
object VideoFrameGrabber {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encodeExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    fun capture(
        playerView: PlayerView,
        positionMs: Long,
        mediaUrl: String?,
        appContext: Context,
        callback: (ByteArray?) -> Unit,
    ) {
        val surface = playerView.videoSurfaceView
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && surface is SurfaceView) {
            captureSurface(surface, positionMs, mediaUrl, appContext, callback)
            return
        }
        if (surface is TextureView) {
            val bitmap = surface.bitmap
            if (bitmap != null) {
                encodeAsync(bitmap, callback)
                return
            }
        }
        encodeExecutor.execute {
            val jpeg = extractAtPosition(appContext, mediaUrl, positionMs)
            mainHandler.post { callback(jpeg) }
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private fun captureSurface(
        surfaceView: SurfaceView,
        positionMs: Long,
        mediaUrl: String?,
        appContext: Context,
        callback: (ByteArray?) -> Unit,
    ) {
        val width = surfaceView.width
        val height = surfaceView.height
        val surface = surfaceView.holder.surface
        if (width <= 0 || height <= 0 || surface == null || !surface.isValid) {
            encodeExecutor.execute {
                val jpeg = extractAtPosition(appContext, mediaUrl, positionMs)
                mainHandler.post { callback(jpeg) }
            }
            return
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(surfaceView, bitmap, { result ->
                if (result == PixelCopy.SUCCESS) {
                    encodeAsync(bitmap, callback)
                } else {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    encodeExecutor.execute {
                        val jpeg = extractAtPosition(appContext, mediaUrl, positionMs)
                        mainHandler.post { callback(jpeg) }
                    }
                }
            }, mainHandler)
        } catch (t: Throwable) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            encodeExecutor.execute {
                val jpeg = extractAtPosition(appContext, mediaUrl, positionMs)
                mainHandler.post { callback(jpeg) }
            }
        }
    }

    private fun encodeAsync(bitmap: Bitmap, callback: (ByteArray?) -> Unit) {
        encodeExecutor.execute {
            val jpeg = try {
                if (isBlankFrame(bitmap)) {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                    null
                } else {
                    VideoFrameJpegEncoder.encodeAndRecycle(bitmap, PinnedVideoThumbnailStore.MAX_EDGE_PX)
                }
            } catch (t: Throwable) {
                if (!bitmap.isRecycled) {
                    bitmap.recycle()
                }
                null
            }
            mainHandler.post { callback(jpeg) }
        }
    }

    /**
     * An unrendered surface copies as empty black. A real dark scene still has some
     * pixels above this floor, so those frames are kept.
     */
    internal fun isBlankFrame(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
            return true
        }
        val xs = intArrayOf(width / 4, width / 2, (width * 3) / 4)
        val ys = intArrayOf(height / 4, height / 2, (height * 3) / 4)
        for (y in ys) {
            for (x in xs) {
                val pixel = bitmap.getPixel(x.coerceIn(0, width - 1), y.coerceIn(0, height - 1))
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                if (r > 8 || g > 8 || b > 8) {
                    return false
                }
            }
        }
        return true
    }

    private fun extractAtPosition(appContext: Context, mediaUrl: String?, positionMs: Long): ByteArray? {
        if (mediaUrl.isNullOrEmpty()) {
            return null
        }
        val retriever = MediaMetadataRetriever()
        val dataSource = OkHttpMediaDataSource(mediaUrl, appContext)
        try {
            retriever.setDataSource(dataSource)
            val timeUs = positionMs.coerceAtLeast(0L) * 1_000L
            val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            if (isBlankFrame(frame)) {
                frame.recycle()
                return null
            }
            return VideoFrameJpegEncoder.encodeAndRecycle(frame, PinnedVideoThumbnailStore.MAX_EDGE_PX)
        } catch (t: Throwable) {
            return null
        } finally {
            try {
                retriever.release()
            } catch (ignored: Exception) {
            }
            try {
                dataSource.close()
            } catch (ignored: Exception) {
            }
        }
    }
}
