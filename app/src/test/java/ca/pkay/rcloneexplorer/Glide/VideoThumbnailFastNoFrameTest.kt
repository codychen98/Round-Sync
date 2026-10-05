package ca.pkay.rcloneexplorer.Glide

import com.bumptech.glide.signature.ObjectKey
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbnailFastNoFrameTest {

    @Test
    fun shouldSkipExoSeeks_onlyFastNoFrameDuringPrefetch() {
        assertTrue(VideoThumbnailFastNoFrame.shouldSkipExoSeeks(true, 60L, true, false))
        assertTrue(VideoThumbnailFastNoFrame.shouldSkipExoSeeks(true, 0L, true, false))
        assertTrue(
            VideoThumbnailFastNoFrame.shouldSkipExoSeeks(
                true,
                VideoThumbnailFastNoFrame.FAST_NO_FRAME_MAX_MS - 1,
                true,
                false,
            ),
        )
        assertFalse(
            VideoThumbnailFastNoFrame.shouldSkipExoSeeks(
                true,
                VideoThumbnailFastNoFrame.FAST_NO_FRAME_MAX_MS,
                true,
                false,
            ),
        )
        assertFalse(VideoThumbnailFastNoFrame.shouldSkipExoSeeks(true, 60L, false, false))
        assertFalse(VideoThumbnailFastNoFrame.shouldSkipExoSeeks(true, 60L, true, true))
        assertFalse(VideoThumbnailFastNoFrame.shouldSkipExoSeeks(false, 60L, true, false))
        assertFalse(VideoThumbnailFastNoFrame.shouldSkipExoSeeks(true, -1L, true, false))
    }

    @Test
    fun markerJpeg_isProbeHit_andNotSvgPoison() {
        val jpeg = VideoThumbnailFastNoFrame.markerJpeg()
        assertTrue(jpeg.size > 4)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        assertEquals(0xD9.toByte(), jpeg[jpeg.size - 1])
        assertFalse(ThumbnailCachePoisonPurge.isPoisoned(jpeg))

        val dir = File(System.getProperty("java.io.tmpdir"), "fast-no-frame-" + System.nanoTime())
        assertTrue(dir.mkdirs())
        val cache = ThumbnailDiskCache(dir, 500L * 1024L * 1024L)
        try {
            val label = "thumbVideo|sample.mov|thumbV2"
            cache.store(ObjectKey(label), jpeg)
            assertTrue(ThumbnailDiskCacheEvictor.isCachedIn(cache, label))
        } finally {
            cache.close()
        }
    }
}
