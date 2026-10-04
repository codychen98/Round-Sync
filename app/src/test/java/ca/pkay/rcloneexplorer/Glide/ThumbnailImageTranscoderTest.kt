package ca.pkay.rcloneexplorer.Glide

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbnailImageTranscoderTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun transcode_smallJpeg_returnsOriginalBytes() {
        val bytes = encode(800, 600, Bitmap.CompressFormat.JPEG)
        assertSame(bytes, ThumbnailImageTranscoder.transcode(bytes))
    }

    @Test
    fun transcode_largePng_returnsBoundedJpeg() {
        val bytes = encode(2560, 1920, Bitmap.CompressFormat.PNG)
        val result = ThumbnailImageTranscoder.transcode(bytes)
        assertNotNull(result)
        assertEquals(ThumbnailImageTranscoder.Format.JPEG, ThumbnailImageTranscoder.sniff(result!!))
        assertEquals(1280 to 960, bounds(result))
    }

    @Test
    fun transcode_svgPayload_isRejected() {
        val withProlog = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()
        val bare = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\"/>".toByteArray()
        assertNull(ThumbnailImageTranscoder.transcode(withProlog))
        assertNull(ThumbnailImageTranscoder.transcode(bare))
        assertNull(ThumbnailImageTranscoder.transcode(ByteArray(0)))
        assertEquals(ThumbnailImageTranscoder.Format.UNKNOWN, ThumbnailImageTranscoder.sniff(withProlog))
    }

    @Test
    fun transcode_gif_passesThroughUnchanged() {
        val gif = "GIF89a".toByteArray() + ByteArray(32)
        assertEquals(ThumbnailImageTranscoder.Format.GIF, ThumbnailImageTranscoder.sniff(gif))
        assertSame(gif, ThumbnailImageTranscoder.transcode(gif))
    }

    @Test
    fun transcode_animatedWebp_passesThroughUnchanged() {
        val webp = "RIFF".toByteArray() + ByteArray(4) + "WEBP".toByteArray() +
            "VP8X".toByteArray() + ByteArray(4) + byteArrayOf(0x02) + ByteArray(16)
        assertEquals(ThumbnailImageTranscoder.Format.WEBP_ANIMATED, ThumbnailImageTranscoder.sniff(webp))
        assertSame(webp, ThumbnailImageTranscoder.transcode(webp))
    }

    @Test
    fun transcode_exifRotate90_isAppliedToOutput() {
        val file = temp.newFile("rotated.jpg")
        file.writeBytes(encode(2000, 1000, Bitmap.CompressFormat.JPEG))
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        val result = ThumbnailImageTranscoder.transcode(file.readBytes())
        assertNotNull(result)
        assertEquals(640 to 1280, bounds(result!!))
    }

    @Test
    fun sampleSizeFor_keepsSampledEdgeAtOrAboveTarget() {
        assertEquals(1, ThumbnailImageTranscoder.sampleSizeFor(1300, 1280))
        assertEquals(2, ThumbnailImageTranscoder.sampleSizeFor(2560, 1280))
        assertEquals(2, ThumbnailImageTranscoder.sampleSizeFor(5000, 1280))
        assertEquals(4, ThumbnailImageTranscoder.sampleSizeFor(5120, 1280))
    }

    private fun encode(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(40, 90, 160))
            val out = ByteArrayOutputStream()
            bitmap.compress(format, 90, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }

    private fun bounds(bytes: ByteArray): Pair<Int, Int> {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return opts.outWidth to opts.outHeight
    }
}
