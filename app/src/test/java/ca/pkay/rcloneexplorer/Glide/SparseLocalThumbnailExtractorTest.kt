package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SparseLocalThumbnailExtractorTest {

    @Test
    fun `constants have expected byte sizes`() {
        assertEquals(24L * 1024L * 1024L, SparseLocalThumbnailExtractor.HEAD_BYTES)
        assertEquals(8L * 1024L * 1024L, SparseLocalThumbnailExtractor.TAIL_BYTES)
        assertEquals(32L * 1024L * 1024L * 1024L, SparseLocalThumbnailExtractor.MAX_FILE_BYTES)
    }

    @Test
    fun `min file size equals head plus tail plus one mib gap`() {
        val expected = SparseLocalThumbnailExtractor.HEAD_BYTES +
            SparseLocalThumbnailExtractor.TAIL_BYTES +
            (1L * 1024L * 1024L)
        assertEquals(expected, SparseLocalThumbnailExtractor.MIN_FILE_BYTES)
    }

    @Test
    fun `head and tail ranges do not overlap at minimum eligible file size`() {
        val fileSize = SparseLocalThumbnailExtractor.MIN_FILE_BYTES
        val headEnd = SparseLocalThumbnailExtractor.HEAD_BYTES
        val tailStart = fileSize - SparseLocalThumbnailExtractor.TAIL_BYTES
        assertTrue("head end must not exceed tail start", headEnd <= tailStart)
    }

    @Test
    fun `range arithmetic is correct for typical large file`() {
        val fileSize = 2_864_891_904L
        val tailStart = fileSize - SparseLocalThumbnailExtractor.TAIL_BYTES
        val totalDownloaded = SparseLocalThumbnailExtractor.HEAD_BYTES + SparseLocalThumbnailExtractor.TAIL_BYTES

        assertEquals(fileSize - 8L * 1024L * 1024L, tailStart)
        assertEquals(32L * 1024L * 1024L, totalDownloaded)
    }

    @Test
    fun `file below min is below threshold`() {
        val belowMin = SparseLocalThumbnailExtractor.MIN_FILE_BYTES - 1L
        assertTrue(belowMin < SparseLocalThumbnailExtractor.MIN_FILE_BYTES)
    }

    @Test
    fun `file above max is above threshold`() {
        val aboveMax = SparseLocalThumbnailExtractor.MAX_FILE_BYTES + 1L
        assertTrue(aboveMax > SparseLocalThumbnailExtractor.MAX_FILE_BYTES)
    }
}
