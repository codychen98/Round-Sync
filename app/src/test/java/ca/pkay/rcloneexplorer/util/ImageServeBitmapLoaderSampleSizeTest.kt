package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageServeBitmapLoaderSampleSizeTest {

    @Test
    fun fullResCameraPhotoIsDownsampledUnderHighFidelityBound() {
        // 26 MP Fuji original vs the 4096 px high-fidelity decode bound. The old algorithm
        // returned 1 here (full-res ~104 MB decode), which crashed the viewer with OOM.
        assertEquals(2, ImageServeBitmapLoader.calculateInSampleSize(6240, 4160, 4096, 4096))
    }

    @Test
    fun fullResCameraPhotoIsDownsampledUnderLowFidelityBound() {
        assertEquals(4, ImageServeBitmapLoader.calculateInSampleSize(6240, 4160, 1600, 1600))
    }

    @Test
    fun imageWithinBoundsIsNotSampled() {
        assertEquals(1, ImageServeBitmapLoader.calculateInSampleSize(1080, 1920, 4096, 4096))
    }

    @Test
    fun decodedDimensionsNeverExceedRequestedBounds() {
        val sizes = listOf(
            640 to 480,
            4096 to 4096,
            4100 to 4100,
            6240 to 4160,
            12000 to 9000,
            300 to 20000,
        )
        val bounds = listOf(1600 to 1600, 4096 to 4096, 1080 to 2400)
        for ((w, h) in sizes) {
            for ((bw, bh) in bounds) {
                val sample = ImageServeBitmapLoader.calculateInSampleSize(w, h, bw, bh)
                assertTrue(
                    "size=${w}x$h bound=${bw}x$bh sample=$sample",
                    w / sample <= bw && h / sample <= bh,
                )
            }
        }
    }

    @Test
    fun invalidDimensionsFallBackToNoSampling() {
        assertEquals(1, ImageServeBitmapLoader.calculateInSampleSize(0, 4160, 4096, 4096))
        assertEquals(1, ImageServeBitmapLoader.calculateInSampleSize(6240, -1, 4096, 4096))
    }
}
