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
        val sourceField = ThumbnailReloadEpoch::class.java.getDeclaredField("lastSourcePositionsMs")
        sourceField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (sourceField.get(null) as MutableMap<String, Long>).clear()
        val usedField = ThumbnailReloadEpoch::class.java.getDeclaredField("usedSourcePositionsMs")
        usedField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (usedField.get(null) as MutableMap<String, MutableSet<Long>>).clear()
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
    fun increment_matchesEncodedServeUrl() {
        // Fixture only: any remote name + folder path works the same way in production code.
        val stable = ThumbnailCacheIdentity.stableServePath("pCloudLock", "Video Archive/Anime/a.mkv")
        ThumbnailReloadEpoch.increment(stable)
        val url = "http://127.0.0.1:29179/auth/pCloudLock/Video%20Archive/Anime/a.mkv"
        assertEquals(1, ThumbnailReloadEpoch.getEpochForVideoUrl(url))
        assertEquals(true, ThumbnailReloadEpoch.consumePreferExoDecode(stable))
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

    @Test
    fun recordSourcePositionMs_tracksPerStablePath() {
        val path = "/pCloudLock/Video Archive/Anime/ep.mkv"
        assertEquals(ThumbnailReloadEpoch.NO_SOURCE_POSITION, ThumbnailReloadEpoch.getLastSourcePositionMs(path))
        assertEquals(emptySet<Long>(), ThumbnailReloadEpoch.getUsedSourcePositionsMs(path))
        ThumbnailReloadEpoch.recordSourcePositionMs(path, 2_000L)
        assertEquals(2_000L, ThumbnailReloadEpoch.getLastSourcePositionMs(path))
        assertEquals(setOf(2_000L), ThumbnailReloadEpoch.getUsedSourcePositionsMs(path))
        ThumbnailReloadEpoch.recordSourcePositionMs(path, 25_000L)
        assertEquals(25_000L, ThumbnailReloadEpoch.getLastSourcePositionMs(path))
        assertEquals(setOf(2_000L, 25_000L), ThumbnailReloadEpoch.getUsedSourcePositionsMs(path))
    }

    @Test
    fun clearUsedSourcePositionsMs_resetsRegistry() {
        val path = "/pCloudLock/Video Archive/Anime/ep.mkv"
        ThumbnailReloadEpoch.recordSourcePositionMs(path, 2_000L)
        ThumbnailReloadEpoch.recordSourcePositionMs(path, 25_000L)
        ThumbnailReloadEpoch.clearUsedSourcePositionsMs(path)
        assertEquals(emptySet<Long>(), ThumbnailReloadEpoch.getUsedSourcePositionsMs(path))
        assertEquals(ThumbnailReloadEpoch.NO_SOURCE_POSITION, ThumbnailReloadEpoch.getLastSourcePositionMs(path))
    }
}
