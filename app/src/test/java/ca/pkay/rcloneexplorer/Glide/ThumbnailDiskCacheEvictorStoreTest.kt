package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ThumbnailDiskCacheEvictorStoreTest {

    @After
    fun tearDown() {
        GlideDiskCacheHolder.resetForTests()
    }

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

    @Test
    fun retainOnlyShownVideoDiskEntry_dropsReloadGenerations() {
        val context = RuntimeEnvironment.getApplication()
        val remote = "pCloudLock"
        val path = "Video Archive/Anime/ep.mkv"
        val legacy = ThumbnailCacheIdentity.legacyEncodedServePath(remote, path)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11)
        val canonical = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, 0)
        val reloadTwo = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, 2)
        val reloadSeven = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, 7)
        ThumbnailDiskCacheEvictor.store(context, com.bumptech.glide.signature.ObjectKey(canonical), jpeg)
        ThumbnailDiskCacheEvictor.store(context, com.bumptech.glide.signature.ObjectKey(reloadTwo), jpeg)
        ThumbnailDiskCacheEvictor.store(context, com.bumptech.glide.signature.ObjectKey(reloadSeven), jpeg)

        ThumbnailDiskCacheEvictor.retainOnlyShownVideoDiskEntry(context, legacy)

        assertTrue(ThumbnailDiskCacheEvictor.isCachedByLabel(context, canonical))
        assertFalse(ThumbnailDiskCacheEvictor.isCachedByLabel(context, reloadTwo))
        assertFalse(ThumbnailDiskCacheEvictor.isCachedByLabel(context, reloadSeven))
    }

    @Test
    fun removeUnshownVideoDiskEntries_countsOnlyReloadCopies() {
        val context = RuntimeEnvironment.getApplication()
        val remote = "pCloudLock"
        val path = "Video Archive/Anime/old-reloads.mkv"
        val legacy = ThumbnailCacheIdentity.legacyEncodedServePath(remote, path)
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x22)
        val canonical = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, 0)
        val reloadOne = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, 1)
        val reloadFour = ThumbnailCacheIdentity.videoDiskCacheKeyLabelForLegacyPath(legacy, 4)
        ThumbnailDiskCacheEvictor.store(context, com.bumptech.glide.signature.ObjectKey(canonical), jpeg)
        ThumbnailDiskCacheEvictor.store(context, com.bumptech.glide.signature.ObjectKey(reloadOne), jpeg)
        ThumbnailDiskCacheEvictor.store(context, com.bumptech.glide.signature.ObjectKey(reloadFour), jpeg)

        val removed = ThumbnailDiskCacheEvictor.removeUnshownVideoDiskEntries(context, legacy)

        org.junit.Assert.assertEquals(2, removed)
        assertTrue(ThumbnailDiskCacheEvictor.isCachedByLabel(context, canonical))
        assertFalse(ThumbnailDiskCacheEvictor.isCachedByLabel(context, reloadOne))
        assertFalse(ThumbnailDiskCacheEvictor.isCachedByLabel(context, reloadFour))
    }
}
