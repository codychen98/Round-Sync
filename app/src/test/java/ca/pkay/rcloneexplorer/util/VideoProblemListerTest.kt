package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoProblemListerTest {

    @Test
    fun relativePathUnderRoot() {
        assertEquals(
            "season1/ep01.mkv",
            VideoProblemLister.toRelativePath("anime/show", "anime/show/season1/ep01.mkv"),
        )
    }

    @Test
    fun relativePathEmptyRoot() {
        assertEquals(
            "anime/show/ep01.mkv",
            VideoProblemLister.toRelativePath("", "anime/show/ep01.mkv"),
        )
    }

    @Test
    fun videoExtensionHeuristic() {
        assertTrue(VideoProblemLister.videoExtensions.contains("mkv"))
        assertTrue(VideoProblemLister.videoExtensions.contains("m2ts"))
        assertFalse(VideoProblemLister.videoExtensions.contains("jpg"))
    }
}
