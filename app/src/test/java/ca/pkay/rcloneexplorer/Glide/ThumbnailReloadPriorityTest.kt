package ca.pkay.rcloneexplorer.Glide

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ThumbnailReloadPriorityTest {

    private val remote = "pCloudLock"
    private val path = "Video Archive/Anime/sample.mkv"

    @Before
    fun reset() {
        setExclusivePath(null)
    }

    @Test
    fun exclusiveTarget_onlyMatchesPinnedFile() {
        val stable = ThumbnailCacheIdentity.stableServePath(remote, path)
        setExclusivePath(stable)
        assertTrue(ThumbnailReloadPriority.isExclusiveActive())
        assertTrue(ThumbnailReloadPriority.isExclusiveTarget(remote, path))
        assertFalse(ThumbnailReloadPriority.isExclusiveTarget(remote, "Video Archive/Anime/other.mkv"))
        assertFalse(
            ThumbnailReloadPriority.shouldDeferThumbnailLoad(remote, path),
        )
        assertTrue(
            ThumbnailReloadPriority.shouldDeferThumbnailLoad(
                remote,
                "Video Archive/Anime/other.mkv",
            ),
        )
        assertFalse(ThumbnailReloadPriority.shouldDeferVideoFetch(stable))
        assertTrue(
            ThumbnailReloadPriority.shouldDeferVideoFetch(
                ThumbnailCacheIdentity.stableServePath(remote, "Video Archive/Anime/other.mkv"),
            ),
        )
    }

    @Test
    fun inactiveExclusive_neverDefers() {
        assertFalse(ThumbnailReloadPriority.isExclusiveActive())
        assertFalse(ThumbnailReloadPriority.shouldDeferThumbnailLoad(remote, path))
        assertFalse(
            ThumbnailReloadPriority.shouldDeferVideoFetch(
                ThumbnailCacheIdentity.stableServePath(remote, path),
            ),
        )
    }

    private fun setExclusivePath(path: String?) {
        val field = ThumbnailReloadPriority::class.java.getDeclaredField("exclusiveStablePath")
        field.isAccessible = true
        field.set(null, path)
    }
}
