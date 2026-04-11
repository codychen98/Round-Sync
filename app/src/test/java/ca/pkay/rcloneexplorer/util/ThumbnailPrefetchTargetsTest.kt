package ca.pkay.rcloneexplorer.util

import ca.pkay.rcloneexplorer.Items.RemoteItem
import io.github.x0b.safdav.file.SafConstants
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailPrefetchTargetsTest {

    @Test
    fun filter_safwRemote_returnsEmpty() {
        val prefs = MemorySharedPreferences()
        val remote = RemoteItem("safw", SafConstants.SAF_REMOTE_NAME)
        val file = PrefetchListingEntry("x.jpg", "x.jpg", 10L, "image/jpeg", isDir = false)
        val out = ThumbnailPrefetchTargets.filterPrefetchListingEntries(
            listOf(file),
            remote,
            prefs,
            sizeLimitBytes = 1_000_000L,
            showThumbnails = true,
        )
        assertEquals(0, out.size)
    }

    @Test
    fun filter_showThumbnailsOff_returnsEmpty() {
        val prefs = MemorySharedPreferences()
        val remote = RemoteItem("r", "s3")
        val file = PrefetchListingEntry("a.jpg", "a.jpg", 10L, "image/jpeg", isDir = false)
        val out = ThumbnailPrefetchTargets.filterPrefetchListingEntries(
            listOf(file),
            remote,
            prefs,
            1_000_000L,
            showThumbnails = false,
        )
        assertEquals(0, out.size)
    }

    @Test
    fun filter_restrictedAllowList_onlyMatchingPaths() {
        val prefs = MemorySharedPreferences()
        val editor = prefs.edit()
        MediaFolderPolicy.writePolicyRows(
            editor,
            "r",
            listOf(MediaFolderPolicyRow("/Photos", thumbnail = true, cache = false)),
        )
        editor.commit()
        val remote = RemoteItem("r", "s3")
        val inPhotos = PrefetchListingEntry("Photos/a.jpg", "a.jpg", 100L, "image/jpeg", isDir = false)
        val outOther = PrefetchListingEntry("Other/b.jpg", "b.jpg", 100L, "image/jpeg", isDir = false)
        val out = ThumbnailPrefetchTargets.filterPrefetchListingEntries(
            listOf(inPhotos, outOther),
            remote,
            prefs,
            1_000_000L,
            showThumbnails = true,
        )
        assertEquals(1, out.size)
        assertEquals("a.jpg", out[0].name)
    }

    @Test
    fun filter_skipsOversizedImages_butKeepsVideo() {
        val prefs = MemorySharedPreferences()
        val remote = RemoteItem("r", "s3")
        val bigImage = PrefetchListingEntry("huge.jpg", "huge.jpg", 500L, "image/jpeg", isDir = false)
        val video = PrefetchListingEntry("clip.mp4", "clip.mp4", 50_000_000L, "video/mp4", isDir = false)
        val out = ThumbnailPrefetchTargets.filterPrefetchListingEntries(
            listOf(bigImage, video),
            remote,
            prefs,
            sizeLimitBytes = 100L,
            showThumbnails = true,
        )
        assertEquals(1, out.size)
        assertEquals("clip.mp4", out[0].name)
    }

}
