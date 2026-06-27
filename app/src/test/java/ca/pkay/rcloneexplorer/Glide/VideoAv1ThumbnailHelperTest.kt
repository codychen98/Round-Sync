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
}
