package ca.pkay.rcloneexplorer.Glide

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream

/**
 * Encodes an extracted video frame as a JPEG whose longest edge is bounded, so a 4K frame does
 * not land in the thumbnail disk cache at full size. Shared by the Glide fetcher and the
 * user-reload direct extract so both write the same kind of entry.
 */
object VideoFrameJpegEncoder {

    const val DEFAULT_MAX_EDGE_PX = 1280
    const val JPEG_QUALITY = 75

    /** Output size for a [width] x [height] frame bounded to [maxEdgePx]; never upscales. */
    @JvmStatic
    fun boundedSize(width: Int, height: Int, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): Pair<Int, Int> {
        val edge = maxOf(width, height)
        if (edge <= 0 || edge <= maxEdgePx) {
            return width to height
        }
        val scale = maxEdgePx.toDouble() / edge
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /**
     * Encodes [frame] (downscaled if needed) and recycles it, mirroring the previous call sites
     * which recycled the frame right after compressing.
     */
    @JvmStatic
    @JvmOverloads
    fun encodeAndRecycle(frame: Bitmap, maxEdgePx: Int = DEFAULT_MAX_EDGE_PX): ByteArray {
        val (w, h) = boundedSize(frame.width, frame.height, maxEdgePx)
        val scaled = if (w != frame.width || h != frame.height) {
            Bitmap.createScaledBitmap(frame, w, h, true)
        } else {
            frame
        }
        try {
            val out = ByteArrayOutputStream(64 * 1024)
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            return out.toByteArray()
        } finally {
            if (scaled !== frame) {
                scaled.recycle()
            }
            frame.recycle()
        }
    }
}
