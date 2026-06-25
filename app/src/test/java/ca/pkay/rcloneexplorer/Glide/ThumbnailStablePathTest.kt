package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ThumbnailStablePathTest {

    @Test
    fun canonicalFromServeUrl_decodesPercentEncodedSpaces() {
        val url = "http://127.0.0.1:29179/authToken/pCloudLock/Video%20Archive/Anime/clip.mkv"
        assertEquals(
            "/pCloudLock/Video Archive/Anime/clip.mkv",
            ThumbnailStablePath.canonicalFromServeUrl(url),
        )
    }

    @Test
    fun canonicalFromServeUrl_matchesStableServePath() {
        val fromItem = ThumbnailCacheIdentity.stableServePath(
            "pCloudLock",
            "Video Archive/Anime/clip.mkv",
        )
        val fromUrl = ThumbnailStablePath.canonicalFromServeUrl(
            "http://127.0.0.1:29179/authToken/pCloudLock/Video%20Archive/Anime/clip.mkv",
        )
        assertEquals(fromItem, fromUrl)
    }

    @Test
    fun legacyPathFromServeUrl_keepsPercentEncodingForDiskCache() {
        val url = "http://127.0.0.1:29179/authToken/pCloudLock/Video%20Archive/Anime/clip.mkv"
        assertEquals(
            "/pCloudLock/Video%20Archive/Anime/clip.mkv",
            ThumbnailStablePath.legacyPathFromServeUrl(url),
        )
        assertNotEquals(
            ThumbnailStablePath.legacyPathFromServeUrl(url),
            ThumbnailStablePath.canonicalFromServeUrl(url),
        )
    }
}
