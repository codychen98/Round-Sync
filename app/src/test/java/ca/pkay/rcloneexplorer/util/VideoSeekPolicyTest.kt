package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSeekPolicyTest {

    @Test
    fun canSeek_requiresSeekableAndCommand() {
        assertTrue(VideoSeekPolicy.canSeekInCurrentWindow(true, true))
        assertFalse(VideoSeekPolicy.canSeekInCurrentWindow(false, true))
        assertFalse(VideoSeekPolicy.canSeekInCurrentWindow(true, false))
        assertFalse(VideoSeekPolicy.canSeekInCurrentWindow(false, false))
    }
}
