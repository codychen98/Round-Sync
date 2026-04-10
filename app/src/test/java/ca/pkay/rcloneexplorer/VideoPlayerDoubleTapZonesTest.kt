package ca.pkay.rcloneexplorer

import ca.pkay.rcloneexplorer.util.VideoDoubleTapHorizontalZone
import ca.pkay.rcloneexplorer.util.VideoPlayerDoubleTapZones
import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlayerDoubleTapZonesTest {

    @Test
    fun horizontalZone_splitsIntoThirds() {
        val w = 300
        assertEquals(VideoDoubleTapHorizontalZone.LEFT, VideoPlayerDoubleTapZones.horizontalZone(0f, w))
        assertEquals(VideoDoubleTapHorizontalZone.LEFT, VideoPlayerDoubleTapZones.horizontalZone(99.9f, w))
        assertEquals(VideoDoubleTapHorizontalZone.CENTER, VideoPlayerDoubleTapZones.horizontalZone(100f, w))
        assertEquals(VideoDoubleTapHorizontalZone.CENTER, VideoPlayerDoubleTapZones.horizontalZone(199.9f, w))
        assertEquals(VideoDoubleTapHorizontalZone.RIGHT, VideoPlayerDoubleTapZones.horizontalZone(200f, w))
        assertEquals(VideoDoubleTapHorizontalZone.RIGHT, VideoPlayerDoubleTapZones.horizontalZone(299f, w))
    }

    @Test
    fun horizontalZone_coercesNonPositiveWidth() {
        assertEquals(VideoDoubleTapHorizontalZone.LEFT, VideoPlayerDoubleTapZones.horizontalZone(0f, 0))
        assertEquals(VideoDoubleTapHorizontalZone.RIGHT, VideoPlayerDoubleTapZones.horizontalZone(0.9f, 0))
    }
}
