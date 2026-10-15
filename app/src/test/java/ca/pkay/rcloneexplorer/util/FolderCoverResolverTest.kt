package ca.pkay.rcloneexplorer.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun pickCollageCandidates_ordersCoverThenImagesThenVideos_andCaps() {
        val entries = listOf(
            PrefetchListingEntry("F/sub", "sub", 0L, "", true),
            PrefetchListingEntry("F/z.jpg", "z.jpg", 10L, "image/jpeg", false),
            PrefetchListingEntry("F/a.jpg", "a.jpg", 10L, "image/jpeg", false),
            PrefetchListingEntry("F/cover.jpg", "cover.jpg", 10L, "image/jpeg", false),
            PrefetchListingEntry("F/clip.mp4", "clip.mp4", 1_000L, "video/mp4", false),
            PrefetchListingEntry("F/b.mp4", "b.mp4", 1_000L, "video/mp4", false),
        )
        val picked = FolderCoverResolver.pickCollageCandidates(entries, 1_000_000L, maxCandidates = 4)
        assertEquals(4, picked.size)
        assertEquals("cover.jpg", picked[0].name)
        assertEquals("z.jpg", picked[1].name)
        assertEquals("a.jpg", picked[2].name)
        assertEquals("clip.mp4", picked[3].name)
    }

    @Test
    fun pickCollageCandidates_skipsOversizedImages() {
        val entries = listOf(
            PrefetchListingEntry("F/huge.jpg", "huge.jpg", 500L, "image/jpeg", false),
            PrefetchListingEntry("F/clip.mp4", "clip.mp4", 50_000L, "video/mp4", false),
        )
        val picked = FolderCoverResolver.pickCollageCandidates(entries, sizeLimitBytes = 100L)
        assertEquals(1, picked.size)
        assertEquals(FolderCoverResolver.CoverKind.VIDEO, picked[0].kind)
    }

    @Test
    fun pickCover_emptyOrDirsOnly_returnsNull() {
        assertNull(FolderCoverResolver.pickCover(emptyList(), 100L))
        assertTrue(
            FolderCoverResolver.pickCollageCandidates(
                listOf(PrefetchListingEntry("F/sub", "sub", 0L, "", true)),
                100L,
            ).isEmpty(),
        )
    }
}
