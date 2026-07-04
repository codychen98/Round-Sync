package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbnailSeekProbeTest {

    @Test
    fun exoSeekAttemptsMs_defaultOrder_prefersEarlySeeks() {
        val seeks = VideoThumbnailSeekProbe.exoSeekAttemptsMs(600_000L, 0)
        assertEquals(0L, seeks[0])
        assertEquals(2_000L, seeks[1])
        assertEquals(5_000L, seeks[2])
        assertEquals(10_000L, seeks[3])
        assertEquals(60_000L, seeks[4])
        assertEquals(150_000L, seeks[5])
    }

    @Test
    fun exoSeekAttemptsMs_reloadEpoch_rotatesStart() {
        val epoch1 = VideoThumbnailSeekProbe.exoSeekAttemptsMs(100_000L, 1)
        assertArrayEquals(
            longArrayOf(2_000L, 5_000L, 10_000L, 10_000L, 25_000L, 0L),
            epoch1,
        )
    }

    @Test
    fun exoSeekAttemptsMs_reloadEpoch_excludesLastSourcePosition() {
        val seeks = VideoThumbnailSeekProbe.exoSeekAttemptsMs(100_000L, 1, 2_000L)
        assertEquals(5_000L, seeks[0])
        for (seek in seeks) {
            assertEquals(false, VideoThumbnailSeekProbe.isSameFrameMs(seek, 2_000L))
        }
    }

    @Test
    fun deprioritizeLastSourceMs_movesNearDuplicatesToEnd() {
        val input = longArrayOf(2_000L, 5_000L, 10_000L, 2_500L)
        val out = VideoThumbnailSeekProbe.deprioritizeLastSourceMs(input, 2_000L)
        assertArrayEquals(longArrayOf(5_000L, 10_000L, 2_500L, 2_000L), out)
    }

    @Test
    fun isSameFrameMs_usesToleranceWindow() {
        assertEquals(true, VideoThumbnailSeekProbe.isSameFrameMs(2_000L, 2_500L))
        assertEquals(false, VideoThumbnailSeekProbe.isSameFrameMs(2_000L, 4_000L))
    }

    @Test
    fun excludeUsedSourceMs_removesNearDuplicates() {
        val input = longArrayOf(2_000L, 5_000L, 10_000L, 2_500L)
        val out = VideoThumbnailSeekProbe.excludeUsedSourceMs(input, setOf(2_000L, 5_000L))
        assertArrayEquals(longArrayOf(10_000L, 2_500L), out)
    }

    @Test
    fun excludeUsedSourceMs_whenAllUsed_returnsEmpty() {
        val input = longArrayOf(2_000L, 5_000L)
        val out = VideoThumbnailSeekProbe.excludeUsedSourceMs(input, setOf(2_000L, 5_000L))
        assertEquals(0, out.size)
    }

    @Test
    fun exoSeekAttemptsMs_reloadEpoch_excludesAllUsedSourcePositions() {
        val used = setOf(2_000L, 5_000L, 10_000L, 25_000L, 0L)
        val seeks = VideoThumbnailSeekProbe.exoSeekAttemptsMs(100_000L, 1, used)
        assertTrue(seeks.isNotEmpty())
        for (seek in seeks) {
            assertEquals(false, VideoThumbnailSeekProbe.matchesAnyUsedSourceMs(seek, used))
        }
    }

    @Test
    fun buildExpandedSeekCandidatesMs_skipsUsedPositions() {
        val expanded = VideoThumbnailSeekProbe.buildExpandedSeekCandidatesMs(
            600_000L,
            3,
            setOf(18_000L, 42_000L),
        )
        assertTrue(expanded.isNotEmpty())
        for (candidate in expanded) {
            assertEquals(
                false,
                VideoThumbnailSeekProbe.matchesAnyUsedSourceMs(candidate, setOf(18_000L, 42_000L)),
            )
        }
    }
}
