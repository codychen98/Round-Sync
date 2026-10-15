package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FolderCoverResolverTest {

    @Test
    fun pickCover_prefersNamedCoverOverOtherImages() {
        val entries = listOf(
            PrefetchListingEntry("F/a.jpg", "a.jpg", 10L, "image/jpeg", false),
            PrefetchListingEntry("F/cover.jpg", "cover.jpg", 10L, "image/jpeg", false),
            PrefetchListingEntry("F/z.jpg", "z.jpg", 10L, "image/jpeg", false),
        )
        val cover = FolderCoverResolver.pickCover(entries, 1_000_000L)!!
        assertEquals("cover.jpg", cover.name)
        assertEquals(FolderCoverResolver.CoverKind.IMAGE, cover.kind)
    }

    @Test
    fun pickCover_namedCoverCaseInsensitive() {
        val entries = listOf(
            PrefetchListingEntry("F/Cover.PNG", "Cover.PNG", 10L, "image/png", false),
        )
        val cover = FolderCoverResolver.pickCover(entries, 1_000_000L)!!
        assertEquals("Cover.PNG", cover.name)
    }

    @Test
    fun pickCover_fallsBackToFirstImageThenVideo() {
        val imageThenVideo = listOf(
            PrefetchListingEntry("F/sub", "sub", 0L, "", true),
            PrefetchListingEntry("F/clip.mp4", "clip.mp4", 1_000L, "video/mp4", false),
            PrefetchListingEntry("F/pic.jpg", "pic.jpg", 10L, "image/jpeg", false),
        )
        assertEquals(
            "pic.jpg",
            FolderCoverResolver.pickCover(imageThenVideo, 1_000_000L)!!.name,
        )

        val videoOnly = listOf(
            PrefetchListingEntry("F/clip.mp4", "clip.mp4", 1_000L, "video/mp4", false),
        )
        val videoCover = FolderCoverResolver.pickCover(videoOnly, 1_000_000L)!!
        assertEquals(FolderCoverResolver.CoverKind.VIDEO, videoCover.kind)
        assertEquals("clip.mp4", videoCover.name)
    }

    @Test
    fun pickCover_skipsOversizedImages_keepsVideoFallback() {
        val entries = listOf(
            PrefetchListingEntry("F/huge.jpg", "huge.jpg", 500L, "image/jpeg", false),
            PrefetchListingEntry("F/clip.mp4", "clip.mp4", 50_000L, "video/mp4", false),
        )
        val cover = FolderCoverResolver.pickCover(entries, sizeLimitBytes = 100L)!!
        assertEquals(FolderCoverResolver.CoverKind.VIDEO, cover.kind)
    }

    @Test
    fun pickCover_emptyOrDirsOnly_returnsNull() {
        assertNull(FolderCoverResolver.pickCover(emptyList(), 100L))
        assertNull(
            FolderCoverResolver.pickCover(
                listOf(PrefetchListingEntry("F/sub", "sub", 0L, "", true)),
                100L,
            ),
        )
    }
}
