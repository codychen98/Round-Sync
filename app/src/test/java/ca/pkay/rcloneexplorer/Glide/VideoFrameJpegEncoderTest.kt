package ca.pkay.rcloneexplorer.Glide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VideoFrameJpegEncoderTest {

    @Test
    fun boundedSize_scalesLongEdgeAndKeepsAspect() {
        assertEquals(1280 to 720, VideoFrameJpegEncoder.boundedSize(1920, 1080))
        assertEquals(720 to 1280, VideoFrameJpegEncoder.boundedSize(2160, 3840))
        assertEquals(640 to 360, VideoFrameJpegEncoder.boundedSize(640, 360))
        assertEquals(1280 to 1280, VideoFrameJpegEncoder.boundedSize(1280, 1280))
        assertEquals(0 to 0, VideoFrameJpegEncoder.boundedSize(0, 0))
    }

    @Test
    fun encode_fullHdFrame_isBoundedTo1280() {
        val frame = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val jpeg = VideoFrameJpegEncoder.encodeAndRecycle(frame)
        assertTrue(frame.isRecycled)
        assertEquals(ThumbnailImageTranscoder.Format.JPEG, ThumbnailImageTranscoder.sniff(jpeg))
        assertEquals(1280 to 720, bounds(jpeg))
    }

    @Test
    fun encode_smallFrame_keepsDimensions() {
        val frame = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val jpeg = VideoFrameJpegEncoder.encodeAndRecycle(frame)
        assertTrue(frame.isRecycled)
        assertEquals(640 to 360, bounds(jpeg))
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }
}
