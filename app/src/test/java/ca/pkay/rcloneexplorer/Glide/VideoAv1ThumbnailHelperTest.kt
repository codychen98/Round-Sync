package ca.pkay.rcloneexplorer.Glide

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
}
