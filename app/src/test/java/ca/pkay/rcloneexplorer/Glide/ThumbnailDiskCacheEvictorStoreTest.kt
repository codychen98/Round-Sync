package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ThumbnailDiskCacheEvictorStoreTest {

    @Test
    fun storeVideoReloadJpeg_usesSingleCanonicalKey_notReloadEpochKey() {
        val context = RuntimeEnvironment.getApplication()
        val remote = "pCloudLock"
        val path = "Video Archive/Anime/ep.mkv"
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

        ThumbnailDiskCacheEvictor.storeVideoReloadJpeg(context, remote, path, 3, jpeg)

        val canonical = ThumbnailCacheIdentity.videoDiskCacheKeyLabel(remote, path, 0)
        val reloadEpoch = ThumbnailCacheIdentity.videoDiskCacheKeyLabel(remote, path, 3)
        assertTrue(ThumbnailDiskCacheEvictor.isCachedByLabel(context, canonical))
        assertFalse(ThumbnailDiskCacheEvictor.isCachedByLabel(context, reloadEpoch))
    }
}
