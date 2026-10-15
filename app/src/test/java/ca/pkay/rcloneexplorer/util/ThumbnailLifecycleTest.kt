package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailLifecycleTest {

    private val remote = RemoteItem("pCloudLock", "pcloud")

    private fun item(path: String, name: String = path.substringAfterLast('/')): FileItem =
        FileItem(remote, path, name, 1L, "2020-01-01T00:00:00Z", "video/mp4", false, false)

    @Test
    fun destinationPathAfterMove_rootVsSubdir() {
        assertEquals(
            "clip.mp4",
            ThumbnailLifecycle.destinationPathAfterMove("pCloudLock", "//pCloudLock", "clip.mp4"),
        )
        assertEquals(
            "Video Archive/Anime/clip.mp4",
            ThumbnailLifecycle.destinationPathAfterMove(
                "pCloudLock",
                "Video Archive/Anime",
                "clip.mp4",
            ),
        )
    }

    @Test
    fun pathsRemovedFromListing_detectsGoneFiles() {
        val previous = listOf(
            item("Video Archive/Anime/a.mp4", "a.mp4"),
            item("Video Archive/Anime/b.mp4", "b.mp4"),
        )
        val current = listOf(item("Video Archive/Anime/a.mp4", "a.mp4"))
        val removed = ThumbnailLifecycle.pathsRemovedFromListing(previous, current)
        assertEquals(1, removed.size)
        assertEquals("Video Archive/Anime/b.mp4", removed[0].path)
    }

    @Test
    fun pathsRemovedFromListing_emptyWhenNoPrevious() {
        assertTrue(ThumbnailLifecycle.pathsRemovedFromListing(null, listOf(item("x"))).isEmpty())
        assertTrue(ThumbnailLifecycle.pathsRemovedFromListing(emptyList(), listOf(item("x"))).isEmpty())
    }
}
