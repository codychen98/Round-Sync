package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test

class ThumbnailReloadEpochTest {

    @Before
    fun reset() {
        val epochField = ThumbnailReloadEpoch::class.java.getDeclaredField("epochs")
        epochField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (epochField.get(null) as MutableMap<String, Int>).clear()
        val pendingField = ThumbnailReloadEpoch::class.java.getDeclaredField("pendingUserReload")
        pendingField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (pendingField.get(null) as MutableSet<String>).clear()
        val preferField = ThumbnailReloadEpoch::class.java.getDeclaredField("preferExoDecode")
        preferField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (preferField.get(null) as MutableSet<String>).clear()
    }

    @Test
    fun increment_bumpsEpochPerStablePath() {
        val path = "/drive/Videos/clip.mp4"
        assertEquals(0, ThumbnailReloadEpoch.get(path))
        assertEquals(1, ThumbnailReloadEpoch.increment(path))
        assertEquals(1, ThumbnailReloadEpoch.get(path))
        assertEquals(2, ThumbnailReloadEpoch.increment(path))
    }

    @Test
    fun videoReloadCacheKey_changesWithEpoch() {
        val key0 = ThumbnailCacheIdentity.videoDataCacheKey("drive", "/Videos/clip.mp4")
        val key1 = ThumbnailCacheIdentity.videoReloadDataCacheKey("drive", "/Videos/clip.mp4", 1)
        val key2 = ThumbnailCacheIdentity.videoReloadDataCacheKey("drive", "/Videos/clip.mp4", 2)

        assertNotEquals(key0, key1)
        assertNotEquals(key1, key2)
    }

    @Test
    fun getEpochForVideoUrl_usesStablePathFromUrl() {
        val stable = "/drive/Videos/clip.mp4"
        ThumbnailReloadEpoch.increment(stable)
        val url = "http://127.0.0.1:5572/auth/drive/Videos/clip.mp4"
        assertEquals(1, ThumbnailReloadEpoch.getEpochForVideoUrl(url))
    }

    @Test
    fun userReloadFlags_areConsumedOnce() {
        val stable = "/drive/Videos/clip.mp4"
        ThumbnailReloadEpoch.markPendingUserReload(stable)
        ThumbnailReloadEpoch.markPreferExoDecode(stable)
        assertEquals(true, ThumbnailReloadEpoch.consumePendingUserReload(stable))
        assertEquals(false, ThumbnailReloadEpoch.consumePendingUserReload(stable))
        assertEquals(true, ThumbnailReloadEpoch.consumePreferExoDecode(stable))
        assertEquals(false, ThumbnailReloadEpoch.consumePreferExoDecode(stable))
    }
}
