package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoAv1ThumbnailHelperTest {

    @Test
    fun isAv1Codec_detectsAv01Mime() {
        assertTrue(VideoAv1ThumbnailHelper.isAv1Codec("video/av01"))
    }

    @Test
    fun isAv1Codec_detectsAv1Substring() {
        assertTrue(VideoAv1ThumbnailHelper.isAv1Codec("video/av1"))
    }

    @Test
    fun isAv1Codec_rejectsH264() {
        assertFalse(VideoAv1ThumbnailHelper.isAv1Codec("video/avc"))
    }

    @Test
    fun isAv1Codec_rejectsNullAndEmpty() {
        assertFalse(VideoAv1ThumbnailHelper.isAv1Codec(null))
        assertFalse(VideoAv1ThumbnailHelper.isAv1Codec(""))
    }

    @Test
    fun computeExpandedHeadEnd_extendsToFirstClusterPlusCap() {
        val actualHead = 48L * 1024L * 1024L
        val firstCluster = 85_352_318L
        val clusterCap = 64L * 1024L * 1024L
        val tailStart = 316_243_451L
        val maxExpanded = 192L * 1024L * 1024L
        assertEquals(
            firstCluster + clusterCap,
            VideoAv1ThumbnailHelper.computeExpandedHeadEnd(
                actualHead,
                firstCluster,
                clusterCap,
                tailStart,
                maxExpanded,
            ),
        )
    }

    @Test
    fun computeExpandedHeadEnd_skipsWhenClusterInsideHead() {
        val actualHead = 48L * 1024L * 1024L
        assertEquals(
            actualHead,
            VideoAv1ThumbnailHelper.computeExpandedHeadEnd(
                actualHead,
                12L * 1024L * 1024L,
                64L * 1024L * 1024L,
                300L * 1024L * 1024L,
                160L * 1024L * 1024L,
            ),
        )
    }

    @Test
    fun computeExpandedHeadEnd_respectsMaxExpandedCap() {
        val actualHead = 48L * 1024L * 1024L
        val maxExpanded = 100L * 1024L * 1024L
        assertEquals(
            maxExpanded,
            VideoAv1ThumbnailHelper.computeExpandedHeadEnd(
                actualHead,
                90L * 1024L * 1024L,
                64L * 1024L * 1024L,
                300L * 1024L * 1024L,
                maxExpanded,
            ),
        )
    }

    @Test
    fun earliestGapCluster_picksEarliestMidGapCandidate() {
        val headBytes = 48L * 1024L * 1024L
        val tailStart = 316_243_451L
        val candidates = listOf(
            120_000_000L,
            85_352_318L,
            200_000_000L,
        )
        assertEquals(
            85_352_318L,
            VideoAv1ThumbnailHelper.earliestGapCluster(candidates, headBytes, tailStart),
        )
    }

    @Test
    fun earliestGapCluster_skipsPositionsInsideHead() {
        val headBytes = 48L * 1024L * 1024L
        val tailStart = 316_243_451L
        val candidates = listOf(12L * 1024L * 1024L, 85_352_318L)
        assertEquals(
            85_352_318L,
            VideoAv1ThumbnailHelper.earliestGapCluster(candidates, headBytes, tailStart),
        )
    }

    @Test
    fun computeExpandedHeadEnd_imaizuminEp05CandidateExpandsHead() {
        val actualHead = 64L * 1024L * 1024L
        val firstCluster = 85_352_318L
        val clusterCap = 96L * 1024L * 1024L
        val tailStart = 307_854_843L
        val maxExpanded = 192L * 1024L * 1024L
        assertEquals(
            firstCluster + clusterCap,
            VideoAv1ThumbnailHelper.computeExpandedHeadEnd(
                actualHead,
                firstCluster,
                clusterCap,
                tailStart,
                maxExpanded,
            ),
        )
    }

    @Test
    fun resolveHeadExpandCluster_ignoresInHeadScanPicksGapCandidate() {
        val headBytes = 64L * 1024L * 1024L
        val tailStart = 307_854_843L
        val inHeadFalsePositive = 403_519L
        val gapCluster = 85_352_318L
        assertEquals(
            gapCluster,
            VideoAv1ThumbnailHelper.resolveHeadExpandCluster(
                headBytes,
                tailStart,
                inHeadFalsePositive,
                gapCluster,
            ),
        )
    }

    @Test
    fun resolveHeadExpandCluster_picksEarliestGapPosition() {
        val headBytes = 64L * 1024L * 1024L
        val tailStart = 307_854_843L
        assertEquals(
            85_352_318L,
            VideoAv1ThumbnailHelper.resolveHeadExpandCluster(
                headBytes,
                tailStart,
                90_000_000L,
                85_352_318L,
            ),
        )
    }

    @Test
    fun resolveHeadExpandCluster_returnsNegativeWhenOnlyInHeadCluster() {
        val headBytes = 64L * 1024L * 1024L
        val tailStart = 307_854_843L
        assertEquals(
            -1L,
            VideoAv1ThumbnailHelper.resolveHeadExpandCluster(
                headBytes,
                tailStart,
                403_519L,
                -1L,
            ),
        )
    }
}
