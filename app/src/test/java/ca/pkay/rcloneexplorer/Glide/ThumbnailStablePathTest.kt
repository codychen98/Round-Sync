package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailStablePathTest {

    @Test
    fun fromServeUrl_decodesPercentEncodedSpaces() {
        val url = "http://127.0.0.1:29179/authToken/pCloudLock/Video%20Archive/Anime/clip.mkv"
        assertEquals(
            "/pCloudLock/Video Archive/Anime/clip.mkv",
            ThumbnailStablePath.fromServeUrl(url),
        )
    }

    @Test
    fun fromServeUrl_matchesStableServePath() {
        val fromItem = ThumbnailCacheIdentity.stableServePath(
            "pCloudLock",
            "Video Archive/Anime/clip.mkv",
        )
        val fromUrl = ThumbnailStablePath.fromServeUrl(
            "http://127.0.0.1:29179/authToken/pCloudLock/Video%20Archive/Anime/clip.mkv",
        )
        assertEquals(fromItem, fromUrl)
    }
}
