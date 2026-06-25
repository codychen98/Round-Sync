package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailReloadResultCacheTest {

    @Test
    fun putAndGet_roundTripJpegBytes() {
        val stable = ThumbnailCacheIdentity.stableServePath("pCloudLock", "Video Archive/Anime/a.mkv")
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        ThumbnailReloadResultCache.put(stable, jpeg)
        assertArrayEquals(jpeg, ThumbnailReloadResultCache.get(stable))
        ThumbnailReloadResultCache.remove(stable)
        assertNull(ThumbnailReloadResultCache.get(stable))
    }
}
